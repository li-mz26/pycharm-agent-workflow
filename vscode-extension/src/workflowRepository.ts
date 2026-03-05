import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { WorkflowDefinition, WorkflowEntry } from './types';

export class WorkflowRepository {
  public loadAll(workspaceRoot: string, workflowRoot: string): WorkflowEntry[] {
    const root = path.isAbsolute(workflowRoot) ? workflowRoot : path.join(workspaceRoot, workflowRoot);
    if (!fs.existsSync(root)) {
      return [];
    }

    const entries = fs.readdirSync(root, { withFileTypes: true })
      .filter((it) => it.isDirectory())
      .map((it) => path.join(root, it.name));

    const workflows: WorkflowEntry[] = [];

    for (const dirPath of entries) {
      const jsonPath = path.join(dirPath, 'workflow.json');
      if (!fs.existsSync(jsonPath)) {
        continue;
      }
      try {
        const raw = fs.readFileSync(jsonPath, 'utf8');
        const definition = JSON.parse(raw) as WorkflowDefinition;
        workflows.push({
          name: definition.name || path.basename(dirPath),
          dirPath,
          jsonPath,
          definition
        });
      } catch (error) {
        vscode.window.showWarningMessage(`读取工作流失败: ${dirPath} (${String(error)})`);
      }
    }

    return workflows.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  }
}
