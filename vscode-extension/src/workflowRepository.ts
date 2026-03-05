import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { WorkflowDefinition, WorkflowEntry } from './types';

export class WorkflowRepository {
  public loadAll(workspaceRoot: string, workflowRoot: string): WorkflowEntry[] {
    const root = path.isAbsolute(workflowRoot) ? workflowRoot : path.join(workspaceRoot, workflowRoot);
    if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
      return [];
    }

    const workflowDirs = this.findWorkflowDirsRecursively(root);
    const workflows: WorkflowEntry[] = [];

    for (const dirPath of workflowDirs) {
      const jsonPath = path.join(dirPath, 'workflow.json');
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

  private findWorkflowDirsRecursively(root: string): string[] {
    const matched = new Set<string>();
    const stack: string[] = [root];

    while (stack.length > 0) {
      const current = stack.pop() as string;
      const workflowJsonPath = path.join(current, 'workflow.json');
      if (fs.existsSync(workflowJsonPath) && fs.statSync(workflowJsonPath).isFile()) {
        matched.add(current);
      }

      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(current, { withFileTypes: true });
      } catch {
        continue;
      }

      for (const entry of entries) {
        if (!entry.isDirectory()) {
          continue;
        }
        if (entry.name.startsWith('.')) {
          continue;
        }
        stack.push(path.join(current, entry.name));
      }
    }

    return Array.from(matched);
  }
}
