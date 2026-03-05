# Agent Workflow VS Code Extension (TypeScript)

基于当前 PyCharm 插件功能的 VS Code 版本原型，提供：

- 工作流目录选择 + 自动扫描 `workflow.json`
- DAG 画布可视化（节点/连线/分支标签）
- 节点配置面板
- 输入/输出/日志页签与“运行”模拟结果
- MCP 开关（基于 `@modelcontextprotocol/sdk` 的 streamable HTTP client）
- 快速打开 VS Code Chat（展开对话）

## 启动开发

```bash
cd vscode-extension
npm install
npm run compile
```

按 F5 启动 Extension Development Host，执行命令：

`Agent Workflow: 打开工作台`

## 打包成可安装 VSIX

```bash
cd vscode-extension
npm install
npm run package
```

默认会在 `vscode-extension/` 目录下生成类似：

- `agent-workflow-vscode-0.0.1.vsix`

如果想固定输出到仓库根目录 `dist/`：

```bash
mkdir -p ../dist
npm run package:out
```

生成文件：

- `dist/agent-workflow-vscode.vsix`

> 注意：`*.vsix` 为构建产物，已在仓库 `.gitignore` 忽略，不需要提交到 Git。

### 安装 VSIX

在 VS Code 中：

1. 打开扩展视图（`Ctrl/Cmd + Shift + X`）
2. 右上角 `...` → `Install from VSIX...`
3. 选择上一步打包得到的 `.vsix`

或者命令行安装：

```bash
code --install-extension dist/agent-workflow-vscode.vsix
```

## 配置项

- `agentWorkflow.workflowRoot`: 默认 `workflows`
- `agentWorkflow.mcpServerUrl`: 默认 `http://127.0.0.1:8788/mcp`
