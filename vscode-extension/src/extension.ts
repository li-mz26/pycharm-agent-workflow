import * as vscode from 'vscode';
import { WorkflowRepository } from './workflowRepository';
import { WorkflowEntry } from './types';
import { McpClientBridge } from './mcpClient';

const WORKBENCH_VIEW_ID = 'agentWorkflow.workbenchView';

class AgentWorkflowViewProvider implements vscode.WebviewViewProvider {
  private view: vscode.WebviewView | undefined;

  constructor(
    private readonly repo: WorkflowRepository,
    private readonly mcp: McpClientBridge
  ) {}

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = { enableScripts: true };
    webviewView.webview.html = getWebviewHtml();

    webviewView.webview.onDidReceiveMessage(async (message) => {
      switch (message?.type) {
        case 'refresh':
          this.pushState();
          break;
        case 'selectWorkflow': {
          const state = resolveState(this.repo);
          const selected = state.workflows.find((it) => it.name === message.payload?.name);
          this.view?.webview.postMessage({ type: 'workflowSelected', payload: selected });
          break;
        }
        case 'pickWorkflowRoot': {
          const picked = await vscode.window.showOpenDialog({
            canSelectMany: false,
            canSelectFolders: true,
            canSelectFiles: false,
            openLabel: '选择工作流目录'
          });
          if (picked?.[0]) {
            await vscode.workspace.getConfiguration('agentWorkflow').update(
              'workflowRoot',
              picked[0].fsPath,
              vscode.ConfigurationTarget.Workspace
            );
            this.pushState();
          }
          break;
        }
        case 'connectMcp': {
          const endpoint = vscode.workspace.getConfiguration('agentWorkflow').get<string>('mcpServerUrl') ?? 'http://127.0.0.1:8788/mcp';
          const result = await this.mcp.connect(endpoint);
          const tools = await this.mcp.listTools();
          this.view?.webview.postMessage({
            type: 'mcpStatus',
            payload: { connected: this.mcp.isConnected(), message: result, tools }
          });
          break;
        }
        case 'disconnectMcp': {
          const result = await this.mcp.disconnect();
          this.view?.webview.postMessage({ type: 'mcpStatus', payload: { connected: false, message: result, tools: [] } });
          break;
        }
        case 'runWorkflow': {
          const name = message.payload?.name as string;
          const state = resolveState(this.repo);
          const selected = state.workflows.find((it) => it.name === name);
          if (!selected) {
            this.view?.webview.postMessage({ type: 'runResult', payload: { output: '未找到工作流', logs: [] } });
            return;
          }

          const output = JSON.stringify(buildMockOutput(selected), null, 2);
          this.view?.webview.postMessage({
            type: 'runResult',
            payload: {
              output,
              logs: [
                `[${new Date().toLocaleTimeString()}] 开始运行: ${selected.name}`,
                `[${new Date().toLocaleTimeString()}] 节点数: ${selected.definition.nodes.length}`,
                `[${new Date().toLocaleTimeString()}] 运行完成`
              ]
            }
          });
          break;
        }
        case 'openChat':
          await vscode.commands.executeCommand('workbench.action.chat.open');
          break;
      }
    });

    this.pushState();
  }

  public async reveal(): Promise<void> {
    await vscode.commands.executeCommand(`${WORKBENCH_VIEW_ID}.focus`);
    this.pushState();
  }

  public requestRefresh(): void {
    this.view?.webview.postMessage({ type: 'requestRefresh' });
  }

  private pushState(): void {
    const state = resolveState(this.repo);
    this.view?.webview.postMessage({
      type: 'state',
      payload: {
        ...state,
        mcpConnected: this.mcp.isConnected()
      }
    });
  }
}

export function activate(context: vscode.ExtensionContext): void {
  const provider = new AgentWorkflowViewProvider(new WorkflowRepository(), new McpClientBridge());

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(WORKBENCH_VIEW_ID, provider, {
      webviewOptions: { retainContextWhenHidden: true }
    }),
    vscode.commands.registerCommand('agentWorkflow.openWorkbench', async () => {
      await provider.reveal();
    }),
    vscode.commands.registerCommand('agentWorkflow.refreshWorkflows', () => {
      provider.requestRefresh();
    })
  );
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

function buildMockOutput(workflow: WorkflowEntry): Record<string, unknown> {
  return {
    workflow: workflow.name,
    raw_data: [],
    cleaned_data: [],
    summary: 'sample_summary',
    message: 'sample_message'
  };
}

function getWebviewHtml(): string {
  const nonce = String(Date.now());
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';" />
<meta name="viewport" content="width=device-width,initial-scale=1.0" />
<title>Agent Workflow</title>
<style>
body{font-family:var(--vscode-font-family);margin:0;padding:12px;color:var(--vscode-foreground)}
.toolbar{display:flex;align-items:center;gap:8px;margin-bottom:8px}
select,button{height:28px}
.main{display:grid;grid-template-columns:2fr 1fr;gap:8px;height:62vh}
.canvas-wrap{border:1px solid var(--vscode-panel-border);position:relative;overflow:auto;background:var(--vscode-editor-background)}
.canvas{position:relative;min-width:900px;min-height:680px}
.node{position:absolute;min-width:90px;padding:8px 12px;border-radius:8px;color:#fff;font-weight:700;box-shadow:0 2px 4px rgba(0,0,0,.2)}
.node small{display:block;opacity:.85;margin-top:4px}
.node.start{background:linear-gradient(90deg,#2e7d32,#66bb6a)}
.node.end{background:linear-gradient(90deg,#b71c1c,#ef5350)}
.node.code{background:linear-gradient(90deg,#1565c0,#42a5f5)}
.node.agent{background:linear-gradient(90deg,#6a1b9a,#ab47bc)}
.node.condition,.node.branch{background:linear-gradient(90deg,#ef6c00,#ffca28)}
.node.http,.node.variable{background:linear-gradient(90deg,#006064,#26c6da)}
.edge-layer{position:absolute;left:0;top:0;pointer-events:none}
.panel{border:1px solid var(--vscode-panel-border);padding:8px;overflow:auto}
.bottom{margin-top:8px;border:1px solid var(--vscode-panel-border)}
.tabs{display:flex;gap:12px;border-bottom:1px solid var(--vscode-panel-border);padding:6px 8px}
.tab{cursor:pointer}
.tab.active{color:var(--vscode-textLink-foreground);font-weight:700}
.tab-content{padding:8px;height:170px;overflow:auto}
pre{margin:0;white-space:pre-wrap;word-break:break-word}
textarea{width:100%;height:150px;background:var(--vscode-input-background);color:var(--vscode-input-foreground)}
</style>
</head>
<body>
  <h3 style="margin:0 0 8px 0;">工作流可视化</h3>
  <div class="toolbar">
    <button id="pickRoot">选择工作流目录</button>
    <label>工作流</label>
    <select id="workflowSelect" style="min-width:280px;"></select>
    <button id="refreshBtn">刷新</button>
    <button id="mcpBtn">关闭MCP</button>
    <button id="chatBtn">展开对话</button>
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
    <button id="runBtn">▶ 运行</button>
    <span id="runningName"></span>
  </div>
  <div class="bottom">
    <div class="tabs">
      <div class="tab active" data-tab="input">输入</div>
      <div class="tab" data-tab="output">输出</div>
      <div class="tab" data-tab="logs">日志</div>
    </div>
    <div class="tab-content" id="input"><textarea id="inputBox">{\n  "raw_data": []\n}</textarea></div>
    <div class="tab-content" id="output" style="display:none;"><pre id="outputBox">等待运行...</pre></div>
    <div class="tab-content" id="logs" style="display:none;"><pre id="logBox">暂无日志</pre></div>
  </div>
<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let state = { workflows: [], selected: null, mcpConnected: false };
const selectEl = document.getElementById('workflowSelect');
const canvasEl = document.getElementById('canvas');
const nodeConfigEl = document.getElementById('nodeConfig');
const mcpBtn = document.getElementById('mcpBtn');

function post(type, payload){ vscode.postMessage({type, payload}); }

document.getElementById('refreshBtn').onclick = () => post('refresh');
document.getElementById('pickRoot').onclick = () => post('pickWorkflowRoot');
document.getElementById('chatBtn').onclick = () => post('openChat');
document.getElementById('runBtn').onclick = () => {
  if(state.selected){ post('runWorkflow', { name: state.selected.name }); }
};
mcpBtn.onclick = () => post(state.mcpConnected ? 'disconnectMcp' : 'connectMcp');

selectEl.onchange = () => post('selectWorkflow', { name: selectEl.value });

for (const tab of document.querySelectorAll('.tab')) {
  tab.onclick = () => {
    document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
    tab.classList.add('active');
    const target = tab.dataset.tab;
    document.getElementById('input').style.display = target === 'input' ? 'block' : 'none';
    document.getElementById('output').style.display = target === 'output' ? 'block' : 'none';
    document.getElementById('logs').style.display = target === 'logs' ? 'block' : 'none';
  };
}

function renderSelect(){
  selectEl.innerHTML = '';
  for (const wf of state.workflows) {
    const option = document.createElement('option');
    option.value = wf.name;
    option.textContent = wf.name;
    selectEl.appendChild(option);
  }
  if (state.selected) { selectEl.value = state.selected.name; }
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
    state.selected = state.workflows[0] || null;
    mcpBtn.textContent = state.mcpConnected ? '关闭MCP' : '启用MCP';
    renderSelect();
    renderCanvas();
  }
  if (msg.type === 'workflowSelected') {
    state.selected = msg.payload || null;
    document.getElementById('runningName').textContent = state.selected ? state.selected.name : '';
    renderCanvas();
  }
  if (msg.type === 'mcpStatus') {
    state.mcpConnected = Boolean(msg.payload.connected);
    mcpBtn.textContent = state.mcpConnected ? '关闭MCP' : '启用MCP';
    document.getElementById('mcpStatus').textContent = msg.payload.message;
    document.getElementById('mcpTools').textContent = (msg.payload.tools || []).length ? 'Tools:\n' + msg.payload.tools.join('\n') : '';
  }
  if (msg.type === 'runResult') {
    document.getElementById('outputBox').textContent = msg.payload.output;
    document.getElementById('logBox').textContent = (msg.payload.logs || []).join('\n');
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
