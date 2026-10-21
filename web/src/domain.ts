// Shared domain types (port of domain/Contract.kt).

export type ToolStatus = 'running' | 'ok' | 'failed'

/** pending waits for the user; sending while the answer is in flight; expired when the turn ended unanswered. */
export type ApprovalStatus = 'pending' | 'sending' | 'answered' | 'expired'

export type TextPart = { type: 'text'; text: string }
export type ReasoningPart = { type: 'reasoning'; text: string }
export type CommentaryPart = { type: 'commentary'; text: string }
export type ToolCallPart = {
  type: 'tool'
  id: string
  tool: string
  argsPreview: string
  resultPreview: string | null
  status: ToolStatus
  durationSec: number | null
}
export type SubagentPart = {
  type: 'subagent'
  id: string
  summary: string
  status: ToolStatus
  durationSec: number | null
}
export type ApprovalPart = {
  type: 'approval'
  requestId: string
  runId: string
  command: string
  description: string
  choices: string[]
  status: ApprovalStatus
  answer: string | null
}

export type MessagePart =
  | TextPart
  | ReasoningPart
  | CommentaryPart
  | ToolCallPart
  | SubagentPart
  | ApprovalPart

export type Role = 'user' | 'assistant'

export type ChatMessage = {
  id: string
  role: Role
  parts: MessagePart[]
  /** epoch millis */
  createdAt: number
  isStreaming?: boolean
  error?: string | null
}

export type ActivityKind = 'thinking' | 'writing' | 'terminal' | 'waiting'
export type ThreadActivity = { kind: ActivityKind; detail: string }

export type ChatThread = {
  id: string
  title: string
  updatedAt: number
  preview: string
  lastMessage: string
  lastFromUser: boolean
  /** Time (ms) up to which lastMessage is valid; used to find stale ones. */
  lastAt: number
  source: string | null
  pinned: boolean
  activity: ThreadActivity | null
}

export type YumemiMood =
  | 'idle'
  | 'happy'
  | 'thinking'
  | 'working'
  | 'surprised'
  | 'error'
  | 'sleeping'

export type ConnectionState =
  | { kind: 'unknown' }
  | { kind: 'checking' }
  | { kind: 'connected' }
  | { kind: 'unauthorized' }
  | { kind: 'not_configured' }
  | { kind: 'unreachable'; message: string }

/** Parsed entry of GET /api/sessions. */
export type RemoteSession = {
  id: string
  title: string
  updatedAt: number
  preview: string
  source: string | null
  startedAt: number
  /** 0 while the session is still open. */
  endedAt: number
}
