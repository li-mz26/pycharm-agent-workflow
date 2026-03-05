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

    const workflows: WorkflowEntry[] = [];
    for (const dirPath of this.findCandidateDirsRecursively(root)) {
      const jsonPath = this.pickWorkflowJsonPath(dirPath);
      if (!jsonPath) {
        continue;
      }

      try {
        const raw = fs.readFileSync(jsonPath, 'utf8');
        const definition = JSON.parse(raw) as WorkflowDefinition;
        if (!this.looksLikeWorkflow(definition)) {
          continue;
        }
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

  private findCandidateDirsRecursively(root: string): string[] {
    const dirs = new Set<string>();
    const stack: string[] = [root];

    while (stack.length > 0) {
      const current = stack.pop() as string;
      dirs.add(current);

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

    return Array.from(dirs);
  }

  private pickWorkflowJsonPath(dirPath: string): string | null {
    const primary = path.join(dirPath, 'workflow.json');
    if (fs.existsSync(primary) && fs.statSync(primary).isFile()) {
      return primary;
    }

    let entries: fs.Dirent[] = [];
    try {
      entries = fs.readdirSync(dirPath, { withFileTypes: true });
    } catch {
      return null;
    }

    const fallback = entries
      .filter((it) => it.isFile() && it.name.endsWith('.json'))
      .map((it) => path.join(dirPath, it.name))[0];

    return fallback ?? null;
  }

  private looksLikeWorkflow(definition: WorkflowDefinition): boolean {
    return Boolean(definition)
      && typeof definition.name === 'string'
      && Array.isArray(definition.nodes)
      && Array.isArray(definition.edges);
  }
}
