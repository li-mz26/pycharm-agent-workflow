export type NodeType = 'start' | 'end' | 'condition' | 'branch' | 'code' | 'agent' | 'http' | 'variable';

export interface WorkflowPosition {
  x: number;
  y: number;
}

export interface WorkflowNode {
  id: string;
  type: NodeType;
  name: string;
  position: WorkflowPosition;
  config?: Record<string, unknown>;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  condition?: string;
}

export interface WorkflowDefinition {
  id?: string;
  name: string;
  description?: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  variables?: Record<string, unknown>;
}

export interface WorkflowEntry {
  name: string;
  dirPath: string;
  jsonPath: string;
  definition: WorkflowDefinition;
}
