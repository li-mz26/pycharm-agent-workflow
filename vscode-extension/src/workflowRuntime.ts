import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { spawn } from 'node:child_process';
import { WorkflowDefinition, WorkflowEdge, WorkflowEntry, WorkflowNode } from './types';

interface RuntimeConfig {
  code?: string;
  codeFile?: string;
  prompt?: string;
  promptFile?: string;
  agentConfigFile?: string;
  promptTemplate?: string;
  systemPrompt?: string;
  apiEndpoint?: string;
  apiKey?: string;
  model?: string;
  branchField?: string;
  branchCases?: Record<string, string>;
  defaultTarget?: string;
  condition?: string;
  method?: string;
  url?: string;
  headers?: Record<string, string>;
  value?: string;
  varName?: string;
}

interface AgentRuntimeConfig {
  apiEndpoint?: string;
  apiKey?: string;
  model?: string;
  systemPrompt?: string;
  promptTemplate?: string;
  prompt?: string;
}

export interface WorkflowRunResult {
  success: boolean;
  logs: string[];
  finalOutput: Record<string, unknown>;
  validationErrors?: string[];
}

export class WorkflowRuntime {
  public async runWorkflow(entry: WorkflowEntry, initialInput: Record<string, unknown>): Promise<WorkflowRunResult> {
    const logs: string[] = [];
    logs.push(`开始运行工作流: ${entry.name}`);
    logs.push(`节点数=${entry.definition.nodes.length}, 边数=${entry.definition.edges.length}`);

    const validationErrors = this.validateWorkflow(entry.definition);
    if (validationErrors.length > 0) {
      logs.push(`校验失败: ${validationErrors.join('; ')}`);
      return { success: false, logs, finalOutput: {}, validationErrors };
    }

    const order = this.topologicalOrder(entry.definition);
    if (order.length !== entry.definition.nodes.length) {
      logs.push('执行失败: 工作流存在环，无法拓扑执行');
      return { success: false, logs, finalOutput: {}, validationErrors: ['workflow 不是 DAG'] };
    }

    const nodeById = new Map(entry.definition.nodes.map((n) => [n.id, n]));
    const incomingEdges = groupBy(entry.definition.edges, (e) => e.target);
    const outgoingEdges = groupBy(entry.definition.edges, (e) => e.source);

    const outputs = new Map<string, Record<string, unknown>>();
    const activeEdgeIds = new Set<string>();

    for (let i = 0; i < order.length; i += 1) {
      const nodeId = order[i];
      const node = nodeById.get(nodeId);
      if (!node) {
        continue;
      }

      const incomingForNode = incomingEdges.get(node.id) ?? [];
      const activeIncoming = incomingForNode.filter((edge) => activeEdgeIds.has(edge.id));
      if (incomingForNode.length > 0 && activeIncoming.length === 0) {
        logs.push(`[${i + 1}/${order.length}] 跳过节点: ${node.name} (${node.type})，未命中条件分支`);
        continue;
      }

      const mergedInputs = this.mergeUpstreamInputs(activeIncoming.map((edge) => edge.source), outputs);
      logs.push(`[${i + 1}/${order.length}] 执行节点: ${node.name} (${node.type})`);

      try {
        const result = await this.executeNode(entry, node, mergedInputs, initialInput);
        outputs.set(node.id, result);
        logs.push(`  - 输出: ${JSON.stringify(result).slice(0, 300)}`);

        const outgoingForNode = outgoingEdges.get(node.id) ?? [];
        if (node.type === 'branch' || node.type === 'condition') {
          const selected = this.selectBranchEdges(node, outgoingForNode, mergedInputs, result);
          selected.forEach((edge) => activeEdgeIds.add(edge.id));
          logs.push(`  - 分支命中: ${selected.map((it) => it.target).join(', ') || '(无)'}`);
        } else {
          outgoingForNode.forEach((edge) => activeEdgeIds.add(edge.id));
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        logs.push(`  - 节点执行失败: ${message}`);
        return { success: false, logs, finalOutput: this.lastOutput(outputs), validationErrors: [`节点 ${node.id} 执行失败: ${message}`] };
      }
    }

    logs.push('工作流运行完成（实际执行）');
    return { success: true, logs, finalOutput: this.lastOutput(outputs) };
  }

  private validateWorkflow(definition: WorkflowDefinition): string[] {
    const errors: string[] = [];
    if (!definition.nodes?.length) {
      errors.push('至少需要 1 个节点');
    }
    if (!definition.edges) {
      errors.push('edges 缺失');
    }
    const ids = new Set<string>();
    for (const n of definition.nodes ?? []) {
      if (ids.has(n.id)) {
        errors.push(`节点 ID 重复: ${n.id}`);
      }
      ids.add(n.id);
    }
    return errors;
  }

  private topologicalOrder(definition: WorkflowDefinition): string[] {
    const inDegree = new Map<string, number>();
    const adjacency = new Map<string, string[]>();

    for (const node of definition.nodes) {
      inDegree.set(node.id, 0);
      adjacency.set(node.id, []);
    }

    for (const edge of definition.edges) {
      adjacency.get(edge.source)?.push(edge.target);
      inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1);
    }

    const queue = Array.from(inDegree.entries())
      .filter(([, deg]) => deg === 0)
      .map(([id]) => id)
      .sort();

    const order: string[] = [];
    while (queue.length > 0) {
      const current = queue.shift() as string;
      order.push(current);
      const targets = (adjacency.get(current) ?? []).slice().sort();
      for (const target of targets) {
        const next = (inDegree.get(target) ?? 0) - 1;
        inDegree.set(target, next);
        if (next === 0) {
          queue.push(target);
        }
      }
    }

    return order;
  }

  private mergeUpstreamInputs(upstreamNodeIds: string[], outputs: Map<string, Record<string, unknown>>): Record<string, unknown> {
    const merged: Record<string, unknown> = {};
    for (const sourceId of upstreamNodeIds) {
      const out = outputs.get(sourceId) ?? {};
      Object.assign(merged, out);
      merged[sourceId] = out;
    }
    return merged;
  }

  private async executeNode(
    entry: WorkflowEntry,
    node: WorkflowNode,
    mergedInputs: Record<string, unknown>,
    initialInput: Record<string, unknown>
  ): Promise<Record<string, unknown>> {
    switch (node.type) {
      case 'start':
        return Object.keys(initialInput).length > 0 ? initialInput : { started: true };
      case 'end':
        return mergedInputs;
      case 'code':
        return this.executeCodeNode(entry, node, mergedInputs);
      case 'agent':
        return this.executeAgentNode(entry, node, mergedInputs);
      case 'http':
        return this.executeHttpNode(node, mergedInputs);
      case 'variable':
        return this.executeVariableNode(node, mergedInputs);
      case 'branch':
      case 'condition':
      default:
        return mergedInputs;
    }
  }

  private async executeCodeNode(entry: WorkflowEntry, node: WorkflowNode, mergedInputs: Record<string, unknown>): Promise<Record<string, unknown>> {
    const config = this.getConfig(node);
    const code = this.resolveCode(entry, node.id, config);
    if (!code) {
      throw new Error('code 节点缺少可执行代码');
    }

    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `workflow-node-${node.id}-`));
    const codePath = path.join(tempDir, 'node.py');
    const inputPath = path.join(tempDir, 'input.json');
    const runnerPath = path.join(tempDir, 'runner.py');

    fs.writeFileSync(codePath, code, 'utf8');
    fs.writeFileSync(inputPath, JSON.stringify(mergedInputs), 'utf8');
    fs.writeFileSync(runnerPath, PYTHON_RUNNER_SCRIPT, 'utf8');

    const pythonCmd = process.env.PYTHON_PATH || 'python3';
    const { stdout, stderr, code: exitCode } = await execProcess(pythonCmd, [runnerPath, codePath, inputPath], 60_000);
    fs.rmSync(tempDir, { recursive: true, force: true });

    if (exitCode !== 0) {
      throw new Error(`python 执行失败(exit=${exitCode}): ${stderr || stdout}`);
    }

    return parseJsonMapFromOutput(stdout);
  }

  private async executeAgentNode(entry: WorkflowEntry, node: WorkflowNode, mergedInputs: Record<string, unknown>): Promise<Record<string, unknown>> {
    const config = this.getConfig(node);
    const configPath = config.agentConfigFile || `nodes/${node.id}_config.json`;
    const filePath = path.join(entry.dirPath, configPath);
    if (!fs.existsSync(filePath)) {
      throw new Error(`agent 节点配置文件不存在: ${configPath}`);
    }

    const runtime = JSON.parse(fs.readFileSync(filePath, 'utf8')) as AgentRuntimeConfig;
    const endpoint = runtime.apiEndpoint;
    const apiKey = runtime.apiKey;
    const model = runtime.model || 'gpt-4o-mini';
    const systemPrompt = runtime.systemPrompt || '你是一个工作流执行助手。';
    const template = runtime.promptTemplate || runtime.prompt || '请根据输入给出结果：{{input_json}}';

    if (!endpoint || !apiKey) {
      throw new Error('agent 配置缺少 apiEndpoint 或 apiKey');
    }

    const renderedPrompt = this.renderTemplate(template, mergedInputs);
    const answer = await this.callChatCompletion(endpoint, apiKey, model, systemPrompt, renderedPrompt);

    return { prompt: renderedPrompt, response: answer };
  }

  private async executeHttpNode(node: WorkflowNode, mergedInputs: Record<string, unknown>): Promise<Record<string, unknown>> {
    const config = this.getConfig(node);
    if (!config.url) {
      throw new Error('http 节点缺少 url');
    }

    const method = (config.method || 'GET').toUpperCase();
    const headers: Record<string, string> = { 'content-type': 'application/json', ...(config.headers ?? {}) };
    const init: RequestInit = { method, headers };

    if (method !== 'GET') {
      init.body = JSON.stringify(mergedInputs);
    }

    const response = await fetch(config.url, init);
    const text = await response.text();
    let parsed: unknown = text;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = text;
    }

    return {
      status: response.status,
      ok: response.ok,
      data: parsed
    };
  }

  private executeVariableNode(node: WorkflowNode, mergedInputs: Record<string, unknown>): Record<string, unknown> {
    const config = this.getConfig(node);
    const variableName = config.varName || node.name || node.id;
    const resolved = this.renderTemplate(config.value || '', mergedInputs);
    return { ...mergedInputs, [variableName]: resolved };
  }

  private resolveCode(entry: WorkflowEntry, nodeId: string, config: RuntimeConfig): string | null {
    if (config.codeFile) {
      const filePath = path.join(entry.dirPath, config.codeFile);
      if (fs.existsSync(filePath)) {
        return fs.readFileSync(filePath, 'utf8');
      }
    }

    const defaultCodePath = path.join(entry.dirPath, 'nodes', `${nodeId}.py`);
    if (fs.existsSync(defaultCodePath)) {
      return fs.readFileSync(defaultCodePath, 'utf8');
    }

    return config.code ?? null;
  }

  private selectBranchEdges(
    node: WorkflowNode,
    outgoing: WorkflowEdge[],
    mergedInputs: Record<string, unknown>,
    executionResult: Record<string, unknown>
  ): WorkflowEdge[] {
    if (outgoing.length === 0) {
      return [];
    }

    const config = this.getConfig(node);
    if (node.type === 'condition') {
      const conditionResult = this.evaluateConditionResult(config.condition || '', mergedInputs, executionResult);
      const matched = outgoing.filter((edge) => this.edgeMatchesConditionResult(edge.condition, conditionResult));
      if (matched.length > 0) {
        return matched;
      }
      if (outgoing.length === 1) {
        return outgoing;
      }
      return conditionResult ? [outgoing[0]] : [outgoing[outgoing.length - 1]];
    }

    const fieldPath = (config.branchField || '').trim();
    if (!fieldPath) {
      return outgoing.slice(0, 1);
    }

    const value = String(this.resolvePath(mergedInputs, fieldPath) ?? '');
    const target = config.branchCases?.[value] || config.defaultTarget;
    if (!target) {
      return [];
    }
    return outgoing.filter((edge) => edge.target === target);
  }

  private evaluateConditionResult(
    expression: string,
    mergedInputs: Record<string, unknown>,
    executionResult: Record<string, unknown>
  ): boolean {
    const trimmed = expression.trim();
    if (!trimmed) {
      const raw = executionResult.condition ?? mergedInputs.condition ?? mergedInputs.result;
      if (typeof raw === 'boolean') return raw;
      if (typeof raw === 'number') return raw !== 0;
      if (typeof raw === 'string') return ['true', '1', '是', '有'].includes(raw.toLowerCase());
      return false;
    }

    const len = trimmed.match(/^len\(([^)]+)\)\s*(==|!=|>=|<=|>|<)\s*(\d+)$/);
    if (len) {
      const key = len[1].trim();
      const op = len[2];
      const right = Number(len[3]);
      const value = this.resolvePath(mergedInputs, key);
      const left = Array.isArray(value)
        ? value.length
        : typeof value === 'string'
          ? value.length
          : value && typeof value === 'object'
            ? Object.keys(value as object).length
            : 0;
      return compareNumber(left, op, right);
    }

    const simple = trimmed.match(/^([a-zA-Z0-9_.]+)\s*(==|!=|>=|<=|>|<)\s*(.+)$/);
    if (!simple) {
      return false;
    }

    const key = simple[1].trim();
    const op = simple[2];
    const rightRaw = simple[3].trim().replace(/^['"]|['"]$/g, '');
    const left = this.resolvePath(mergedInputs, key);

    const leftNum = typeof left === 'number' ? left : Number.NaN;
    const rightNum = Number(rightRaw);
    if (!Number.isNaN(leftNum) && !Number.isNaN(rightNum)) {
      return compareNumber(leftNum, op, rightNum);
    }

    return compareText(String(left ?? ''), op, rightRaw);
  }

  private edgeMatchesConditionResult(label: string | undefined, conditionResult: boolean): boolean {
    const normalized = (label || '').trim().toLowerCase();
    if (!normalized) {
      return false;
    }

    const trueLabels = ['true', 'yes', 'y', '1', '有', '是', '命中', '通过', 'success'];
    const falseLabels = ['false', 'no', 'n', '0', '无', '否', '不通过', '失败', 'else', 'default'];

    return conditionResult
      ? trueLabels.some((it) => normalized.includes(it))
      : falseLabels.some((it) => normalized.includes(it));
  }

  private resolvePath(data: unknown, pathExpression: string): unknown {
    const parts = pathExpression.split('.');
    let current: unknown = data;
    for (const part of parts) {
      if (!current || typeof current !== 'object' || Array.isArray(current)) {
        return undefined;
      }
      current = (current as Record<string, unknown>)[part];
    }
    return current;
  }

  private renderTemplate(template: string, mergedInputs: Record<string, unknown>): string {
    return template.replace(/\{\{\s*([a-zA-Z0-9_.]+)\s*}}/g, (_, key: string) => {
      if (key === 'input_json') {
        return JSON.stringify(mergedInputs);
      }
      const value = this.resolvePath(mergedInputs, key);
      return value === undefined || value === null ? '' : String(value);
    });
  }

  private async callChatCompletion(endpoint: string, apiKey: string, model: string, systemPrompt: string, prompt: string): Promise<string> {
    const normalized = endpoint.replace(/\/$/, '');
    const url = normalized.endsWith('/chat/completions') ? normalized : `${normalized}/chat/completions`;

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model,
        temperature: 0.2,
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: prompt }
        ]
      })
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`LLM 请求失败(${response.status}): ${text}`);
    }

    const data = (await response.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    return data.choices?.[0]?.message?.content || '';
  }

  private getConfig(node: WorkflowNode): RuntimeConfig {
    return (node.config ?? {}) as RuntimeConfig;
  }

  private lastOutput(outputs: Map<string, Record<string, unknown>>): Record<string, unknown> {
    const list = Array.from(outputs.values());
    return list.length > 0 ? list[list.length - 1] : {};
  }
}

function groupBy<T>(items: T[], keySelector: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of items) {
    const key = keySelector(item);
    const list = map.get(key) ?? [];
    list.push(item);
    map.set(key, list);
  }
  return map;
}

function compareNumber(left: number, op: string, right: number): boolean {
  switch (op) {
    case '==': return left === right;
    case '!=': return left !== right;
    case '>': return left > right;
    case '<': return left < right;
    case '>=': return left >= right;
    case '<=': return left <= right;
    default: return false;
  }
}

function compareText(left: string, op: string, right: string): boolean {
  switch (op) {
    case '==': return left === right;
    case '!=': return left !== right;
    default: return false;
  }
}

function parseJsonMapFromOutput(stdout: string): Record<string, unknown> {
  const line = stdout.split(/\r?\n/).map((it) => it.trim()).filter(Boolean).pop() ?? '{}';
  const parsed = JSON.parse(line) as unknown;
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {};
  }
  return parsed as Record<string, unknown>;
}

function execProcess(command: string, args: string[], timeoutMs: number): Promise<{ stdout: string; stderr: string; code: number | null }> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';

    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('python 执行超时(>60s)'));
    }, timeoutMs);

    child.stdout.on('data', (chunk) => {
      stdout += String(chunk);
    });

    child.stderr.on('data', (chunk) => {
      stderr += String(chunk);
    });

    child.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });

    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ stdout, stderr, code });
    });
  });
}

const PYTHON_RUNNER_SCRIPT = `
import json
import sys

code_path = sys.argv[1]
input_path = sys.argv[2]

namespace = {}
with open(code_path, "r", encoding="utf-8") as f:
    source = f.read()

exec(source, namespace)
if "main" not in namespace:
    raise RuntimeError("Python 节点必须定义 main(inputs) 函数")

with open(input_path, "r", encoding="utf-8") as f:
    inputs = json.load(f)

result = namespace["main"](inputs)
if result is None:
    result = {}
print(json.dumps(result, ensure_ascii=False))
`;
