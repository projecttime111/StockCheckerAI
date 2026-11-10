import { api, Page } from "./api";

export type Conversation = { id: string; title: string; createdAt: string; updatedAt: string };
export type Evidence = { id?: string; tool?: string; title?: string; data?: unknown; asOf?: string; period?: { from?: string; through?: string; previousFrom?: string; previousThrough?: string; timeZone?: string }; limitations?: string[] };
export type ChatMessage = { id: string; role: string; content: string; evidence?: Record<string, Evidence>; createdAt: string; restricted: boolean; status?: string; requestId?: string };
export type ChatRequest = { conversationId?: string; message: string; requestId: string };
export type ChatResponse = { conversationId: string; response: string; evidence: Record<string, Evidence>; messageId: string; requestId: string; status: "COMPLETED" | "FALLBACK"; provider: string; model?: string; generatedAt: string; limitations: string[] };

export const assistantApi = {
  conversations: (page: number) => api<Page<Conversation>>(`/api/ai/conversations?page=${page}&size=20`),
  messages: (id: string, page: number) => api<Page<ChatMessage>>(`/api/ai/conversations/${encodeURIComponent(id)}/messages?page=${page}&size=50`),
  remove: (id: string) => api<void>(`/api/ai/conversations/${encodeURIComponent(id)}`, { method: "DELETE" }),
  chat: (body: ChatRequest, signal: AbortSignal) => api<ChatResponse>("/api/ai/assistant/chat", { method: "POST", body: JSON.stringify(body), signal, timeoutMs: 100_000 })
};
