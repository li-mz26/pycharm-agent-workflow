# Agent Workflow VS Code Extension (TypeScript)

基于当前 PyCharm 插件功能的 VS Code 版本原型，提供：

- 工作流目录选择 + 自动扫描 `workflow.json`
- DAG 画布可视化（节点/连线/分支标签）
- 节点配置面板
- 输入/输出/日志页签与“运行”模拟结果
- MCP 开关（基于 `@modelcontextprotocol/sdk` 的 streamable HTTP client）
- 快速打开 VS Code Chat（展开对话）

## 启动

```bash
cd vscode-extension
npm install
npm run compile
```

按 F5 启动 Extension Development Host，执行命令：

`Agent Workflow: 打开工作台`

## 配置项

- `agentWorkflow.workflowRoot`: 默认 `workflows`
- `agentWorkflow.mcpServerUrl`: 默认 `http://127.0.0.1:8788/mcp`
