import * as vscode from 'vscode';
import { WorkflowRepository } from './workflowRepository';
import { WorkflowEntry } from './types';
import { McpClientBridge } from './mcpClient';
import { WorkflowRuntime } from './workflowRuntime';

export function activate(context: vscode.ExtensionContext): void {
  const repo = new WorkflowRepository();
  const mcp = new McpClientBridge();
  const output = vscode.window.createOutputChannel('agent workflow');
  const runtime = new WorkflowRuntime();

  let panel: vscode.WebviewPanel | undefined;

  const pushState = () => {
    const state = resolveState(repo);
    output.appendLine(`扫描工作流: 根目录=${state.workflowRoot}, 命中=${state.workflows.length}`);
    panel?.webview.postMessage({
      type: 'state',
      payload: {
        ...state,
        mcpConnected: mcp.isConnected()
      }
    });
  };

  const pickWorkflowRoot = async () => {
    const picked = await vscode.window.showOpenDialog({
      canSelectMany: false,
      canSelectFolders: true,
      canSelectFiles: false,
      openLabel: '选择工作流目录'
    });

    if (!picked?.[0]) {
      return;
    }

    await vscode.workspace.getConfiguration('agentWorkflow').update(
      'workflowRoot',
      picked[0].fsPath,
      vscode.ConfigurationTarget.Workspace
    );

    output.appendLine(`设置工作流根目录: ${picked[0].fsPath}`);
    panel?.webview.postMessage({ type: 'workflowRootChanged', payload: { workflowRoot: picked[0].fsPath } });
    pushState();
  };

  const openDisposable = vscode.commands.registerCommand('agentWorkflow.openWorkbench', async () => {
    if (panel) {
      panel.reveal(vscode.ViewColumn.Beside);
      output.show(true);
      output.appendLine('已聚焦 Agent Workflow 工作台');
      return;
    }

    panel = vscode.window.createWebviewPanel(
      'agentWorkflowWorkbench',
      'Agent Workflow',
      vscode.ViewColumn.Beside,
      {
        enableScripts: true,
        retainContextWhenHidden: true
      }
    );

    panel.webview.html = getWebviewHtml();
    output.show(true);
    output.appendLine('展示 workflow 运行日志');

    panel.webview.onDidReceiveMessage(async (message) => {
      switch (message?.type) {
        case 'refresh':
          pushState();
          output.appendLine('刷新工作流列表');
          break;
        case 'selectWorkflow': {
          const state = resolveState(repo);
          const selected = state.workflows.find((it) => it.name === message.payload?.name);
          panel?.webview.postMessage({ type: 'workflowSelected', payload: selected });
          if (selected) {
            output.appendLine(`切换工作流: ${selected.name}`);
          }
          break;
        }
        case 'pickWorkflowRoot':
          output.appendLine('收到 UI 请求: 选择工作流目录');
          await pickWorkflowRoot();
          break;
        case 'connectMcp': {
          const endpoint = vscode.workspace.getConfiguration('agentWorkflow').get<string>('mcpServerUrl') ?? 'http://127.0.0.1:8788/mcp';
          const result = await mcp.connect(endpoint);
          const tools = await mcp.listTools();
          panel?.webview.postMessage({ type: 'mcpStatus', payload: { connected: mcp.isConnected(), message: result, tools } });
          output.appendLine(result);
          break;
        }
        case 'disconnectMcp': {
          const result = await mcp.disconnect();
          panel?.webview.postMessage({ type: 'mcpStatus', payload: { connected: false, message: result, tools: [] } });
          output.appendLine(result);
          break;
        }
        case 'runWorkflow': {
          panel?.webview.postMessage({ type: 'runState', payload: { running: true } });
          const name = message.payload?.name as string;
          const rawInput = message.payload?.input as string | undefined;
          const state = resolveState(repo);
          const selected = state.workflows.find((it) => it.name === name);
          if (!selected) {
            panel?.webview.postMessage({ type: 'runState', payload: { running: false } });
            output.appendLine('运行失败: 未找到工作流');
            return;
          }

          let initialInput: Record<string, unknown> = {};
          try {
            if (rawInput?.trim()) {
              const parsed = JSON.parse(rawInput) as unknown;
              if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                initialInput = parsed as Record<string, unknown>;
              }
            }
          } catch {
            panel?.webview.postMessage({ type: 'runState', payload: { running: false } });
            output.appendLine('运行失败: 输入 JSON 格式错误');
            output.show(true);
            return;
          }

          try {
            const result = await runtime.runWorkflow(selected, initialInput);
            output.appendLine(`=== 工作流输出: ${selected.name} ===`);
            output.appendLine(JSON.stringify(result.finalOutput, null, 2));
            result.logs.forEach((line) => output.appendLine(line));
            output.appendLine('=== 结束 ===');
            output.show(true);
          } finally {
            panel?.webview.postMessage({ type: 'runState', payload: { running: false } });
          }
          break;
        }
        case 'openChat': {
          await vscode.commands.executeCommand('workbench.action.chat.open');
          break;
        }
      }
    });

    panel.onDidDispose(() => {
      panel = undefined;
      output.appendLine('Agent Workflow 工作台已关闭');
    });

    pushState();
  });

  const refreshDisposable = vscode.commands.registerCommand('agentWorkflow.refreshWorkflows', () => {
    if (panel) {
      panel.webview.postMessage({ type: 'requestRefresh' });
      output.appendLine('触发工作台刷新');
      return;
    }
    vscode.window.showInformationMessage('请先打开 Agent Workflow 工作台');
  });

  const pickRootDisposable = vscode.commands.registerCommand('agentWorkflow.pickWorkflowRoot', async () => {
    await pickWorkflowRoot();
  });

  context.subscriptions.push(openDisposable, refreshDisposable, pickRootDisposable, output);
}

function resolveState(repo: WorkflowRepository): { workflowRoot: string; workflows: WorkflowEntry[] } {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  const workflowRoot = vscode.workspace.getConfiguration('agentWorkflow').get<string>('workflowRoot') ?? 'workflows';
  if (!workspaceFolder) {
    return { workflowRoot, workflows: [] };
  }
  const workflows = repo.loadAll(workspaceFolder.uri.fsPath, workflowRoot);
  return { workflowRoot, workflows };
}

function getWebviewHtml(): string {
  const nonce = createNonce();
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';" />
<meta name="viewport" content="width=device-width,initial-scale=1.0" />
<title>Agent Workflow</title>
<style>
html,body{height:100%}
body{font-family:var(--vscode-font-family);margin:0;padding:10px;box-sizing:border-box;color:var(--vscode-foreground);display:flex;flex-direction:column;overflow:hidden}
.toolbar{display:flex;align-items:center;gap:8px;margin-bottom:8px}
select,button{height:28px}
.main{display:grid;grid-template-columns:2fr 1fr;gap:8px;flex:1;min-height:0}
.canvas-wrap{border:1px solid var(--vscode-panel-border);position:relative;overflow:auto;background:var(--vscode-editor-background);min-height:0}
.canvas{position:relative;min-width:900px;min-height:100%}
.node{position:absolute;min-width:90px;padding:8px 12px;border-radius:8px;color:#fff;font-weight:700;box-shadow:0 2px 4px rgba(0,0,0,.2)}
.node small{display:block;opacity:.85;margin-top:4px}
.node.start{background:linear-gradient(90deg,#2e7d32,#66bb6a)}
.node.end{background:linear-gradient(90deg,#b71c1c,#ef5350)}
.node.code{background:linear-gradient(90deg,#1565c0,#42a5f5)}
.node.agent{background:linear-gradient(90deg,#6a1b9a,#ab47bc)}
.node.condition,.node.branch{background:linear-gradient(90deg,#ef6c00,#ffca28)}
.node.http,.node.variable{background:linear-gradient(90deg,#006064,#26c6da)}
.edge-layer{position:absolute;left:0;top:0;pointer-events:none}
.panel{border:1px solid var(--vscode-panel-border);padding:8px;overflow:auto;min-height:0}
.bottom{margin-top:8px;border:1px solid var(--vscode-panel-border);height:160px;display:flex;flex-direction:column;min-height:0;flex-shrink:0}
.bottom-title{padding:6px 8px;border-bottom:1px solid var(--vscode-panel-border)}
.bottom-content{padding:8px;flex:1;min-height:0}
textarea{width:100%;height:100%;box-sizing:border-box;background:var(--vscode-input-background);color:var(--vscode-input-foreground)}
</style>
</head>
<body>
  <h3 style="margin:0 0 8px 0;">工作流可视化</h3>
  <div class="toolbar">
    <button id="pickRoot" type="button">工作流文件夹</button>
    <label>工作流选择</label>
    <select id="workflowSelect" style="min-width:280px;"></select>
    <button id="mcpBtn" type="button">启用/关闭mcp</button>
    <button id="refreshBtn" type="button">刷新</button>
    <span id="workflowRootLabel" style="margin-left:auto;opacity:.8;font-size:12px;"></span>
  </div>
  <div class="main">
    <div class="canvas-wrap"><div class="canvas" id="canvas"></div></div>
    <div class="panel">
      <h4 style="margin:0 0 8px 0;">节点配置</h4>
      <pre id="nodeConfig">请选择一个节点进行配置</pre>
      <hr />
      <pre id="mcpStatus">MCP: 未连接</pre>
      <pre id="mcpTools"></pre>
    </div>
  </div>
  <div style="display:flex;align-items:center;gap:8px;margin-top:8px;">
    <button id="runBtn" type="button">▶ 工作流运行</button>
    <button id="chatBtn" type="button">展开对话</button>
    <span id="runningName"></span>
    <span id="runStateLabel" style="opacity:.8;font-size:12px;"></span>
  </div>
  <div class="bottom">
    <div class="bottom-title">工作流输入</div>
    <div class="bottom-content"><textarea id="inputBox">{
  "raw_data": []
}</textarea></div>
  </div>
<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let state = { workflows: [], selected: null, mcpConnected: false };
const selectEl = document.getElementById('workflowSelect');
const canvasEl = document.getElementById('canvas');
const nodeConfigEl = document.getElementById('nodeConfig');
const mcpBtn = document.getElementById('mcpBtn');
const runBtn = document.getElementById('runBtn');
const rootLabel = document.getElementById('workflowRootLabel');
const runStateLabel = document.getElementById('runStateLabel');

function post(type, payload){ vscode.postMessage({type, payload}); }

document.getElementById('refreshBtn').onclick = () => post('refresh');
document.getElementById('pickRoot').onclick = () => post('pickWorkflowRoot');
document.getElementById('chatBtn').onclick = () => post('openChat');
runBtn.onclick = () => {
  if(!state.selected){
    runStateLabel.textContent = '请先选择工作流';
    return;
  }
  post('runWorkflow', { name: state.selected.name, input: document.getElementById('inputBox').value });
};
mcpBtn.onclick = () => post(state.mcpConnected ? 'disconnectMcp' : 'connectMcp');

selectEl.onchange = () => post('selectWorkflow', { name: selectEl.value });

function renderSelect(){
  selectEl.innerHTML = '';
  if (!state.workflows.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = '(未发现工作流)';
    selectEl.appendChild(option);
    selectEl.disabled = true;
    return;
  }

  selectEl.disabled = false;
  for (const wf of state.workflows) {
    const option = document.createElement('option');
    option.value = wf.name;
    option.textContent = wf.name;
    selectEl.appendChild(option);
  }
  if (state.selected) {
    selectEl.value = state.selected.name;
  }
}

function renderCanvas(){
  canvasEl.innerHTML = '';
  if (!state.selected) {
    canvasEl.textContent = '暂无工作流';
    return;
  }

  const { nodes, edges } = state.selected.definition;
  const width = Math.max(900, ...nodes.map(n => n.position.x + 200));
  const height = Math.max(680, ...nodes.map(n => n.position.y + 140));
  canvasEl.style.width = width + 'px';
  canvasEl.style.height = height + 'px';

  const edgeLayer = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  edgeLayer.setAttribute('class', 'edge-layer');
  edgeLayer.setAttribute('width', width);
  edgeLayer.setAttribute('height', height);
  edgeLayer.style.width = width + 'px';
  edgeLayer.style.height = height + 'px';

  for (const edge of edges) {
    const source = nodes.find(n => n.id === edge.source);
    const target = nodes.find(n => n.id === edge.target);
    if (!source || !target) continue;
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    const x1 = source.position.x + 90; const y1 = source.position.y + 44;
    const x2 = target.position.x + 50; const y2 = target.position.y;
    const d = 'M ' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + (y1+45) + ', ' + x2 + ' ' + (y2-45) + ', ' + x2 + ' ' + y2;
    path.setAttribute('d', d);
    path.setAttribute('stroke', '#8b8b8b');
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke-width', '2');
    edgeLayer.appendChild(path);

    if (edge.condition) {
      const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      label.setAttribute('x', String((x1 + x2) / 2));
      label.setAttribute('y', String((y1 + y2) / 2 - 4));
      label.setAttribute('fill', '#ff9800');
      label.setAttribute('font-size', '12');
      label.textContent = edge.condition;
      edgeLayer.appendChild(label);
    }
  }
  canvasEl.appendChild(edgeLayer);

  for (const node of nodes) {
    const el = document.createElement('div');
    el.className = 'node ' + node.type;
    el.style.left = node.position.x + 'px';
    el.style.top = node.position.y + 'px';
    el.innerHTML = '<div>' + node.name + '</div><small>' + String(node.type).toUpperCase() + '</small>';
    el.onclick = () => {
      nodeConfigEl.textContent = JSON.stringify(node, null, 2);
    };
    canvasEl.appendChild(el);
  }
}

window.addEventListener('message', (event) => {
  const msg = event.data;
  if (msg.type === 'state') {
    state.workflows = msg.payload.workflows || [];
    state.mcpConnected = Boolean(msg.payload.mcpConnected);
    const previous = state.selected?.name;
    const selected = state.workflows.find(w => w.name === previous) || state.workflows[0] || null;
    state.selected = selected;
    mcpBtn.textContent = state.mcpConnected ? '关闭mcp' : '启用mcp';
    rootLabel.textContent = '目录: ' + (msg.payload.workflowRoot || 'workflows');
    renderSelect();
    document.getElementById('runningName').textContent = state.selected ? state.selected.name : '';
    if (!state.selected) { runStateLabel.textContent = '未发现可运行工作流'; } else { runStateLabel.textContent = ''; }
    renderCanvas();
  }
  if (msg.type === 'workflowSelected') {
    state.selected = msg.payload || null;
    document.getElementById('runningName').textContent = state.selected ? state.selected.name : '';
    runStateLabel.textContent = '';
    renderCanvas();
  }
  if (msg.type === 'workflowRootChanged') {
    rootLabel.textContent = '目录: ' + msg.payload.workflowRoot;
  }
  if (msg.type === 'mcpStatus') {
    state.mcpConnected = Boolean(msg.payload.connected);
    mcpBtn.textContent = state.mcpConnected ? '关闭mcp' : '启用mcp';
    document.getElementById('mcpStatus').textContent = msg.payload.message;
    document.getElementById('mcpTools').textContent = (msg.payload.tools || []).length ? 'Tools:\n' + msg.payload.tools.join('\n') : '';
  }
  if (msg.type === 'runState') {
    const running = Boolean(msg.payload.running);
    runBtn.disabled = running;
    runBtn.textContent = running ? '运行中...' : '▶ 工作流运行';
    runStateLabel.textContent = running ? '执行中，详情请看底部 Output: agent workflow' : '';
  }
  if (msg.type === 'requestRefresh') {
    post('refresh');
  }
});
</script>
</body>
</html>`;
}

export function deactivate(): void {}


function createNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < 24; i += 1) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}
