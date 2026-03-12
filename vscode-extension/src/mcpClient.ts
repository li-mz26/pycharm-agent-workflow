export class McpClientBridge {
  private client: unknown | undefined;
  private connected = false;

  public isConnected(): boolean {
    return this.connected;
  }

  public async connect(serverUrl: string): Promise<string> {
    if (this.connected) {
      return `MCP 已连接: ${serverUrl}`;
    }

    try {
      const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
      const { StreamableHTTPClientTransport } = await import('@modelcontextprotocol/sdk/client/streamableHttp.js');

      const transport = new StreamableHTTPClientTransport(new URL(serverUrl));
      const client = new Client({ name: 'agent-workflow-vscode', version: '0.0.1' });
      await client.connect(transport);

      this.client = client;
      this.connected = true;
      return `MCP 连接成功: ${serverUrl}`;
    } catch (error) {
      this.client = undefined;
      this.connected = false;
      return `MCP 连接失败: ${String(error)}`;
    }
  }

  public async disconnect(): Promise<string> {
    if (!this.connected || !this.client) {
      return 'MCP 当前未连接';
    }

    try {
      const closeable = this.client as { close?: () => Promise<void> };
      await closeable.close?.();
    } finally {
      this.client = undefined;
      this.connected = false;
    }

    return 'MCP 已关闭';
  }

  public async listTools(): Promise<string[]> {
    if (!this.connected || !this.client) {
      return [];
    }

    try {
      const candidate = this.client as {
        listTools?: () => Promise<{ tools?: Array<{ name?: string }> }>;
      };
      if (!candidate.listTools) {
        return [];
      }
      const result = await candidate.listTools();
      return result.tools?.map((it) => it.name ?? 'unknown').filter(Boolean) ?? [];
    } catch {
      return [];
    }
  }
}
