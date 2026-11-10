"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useEffect, useRef, useState } from "react";
import { MessageSquare, Plus, Send, Square, Trash2 } from "lucide-react";
import { AppShell } from "./AppShell";
import { AssistantEvidence } from "./AssistantEvidence";
import { Field, PrimaryButton, SecondaryButton } from "./FormControls";
import { QueryFeedback, MutationFeedback } from "./QueryFeedback";
import { Pagination } from "./Pagination";
import { LoadingState } from "./LoadingState";
import { Panel } from "./Panel";
import { assistantApi, ChatRequest, ChatResponse, Conversation } from "@/lib/assistant";
import { DEMO_MODE, newOperationId } from "@/lib/api";

const prompts = ["What should I reorder this week?", "Which products are dead stock?", "Why did my profit drop this month?", "Which customer has the highest outstanding?", "Which imported products need cleanup?"];

export function SavedAssistant() {
  const client = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [message, setMessage] = useState("");
  const [pending, setPending] = useState<ChatRequest | null>(null);
  const [notice, setNotice] = useState("");
  const [lastAnswer, setLastAnswer] = useState<ChatResponse | null>(null);
  const [deleting, setDeleting] = useState<Conversation | null>(null);
  const controller = useRef<AbortController | null>(null);
  const activeRequest = useRef<string | null>(null);
  const cancelled = useRef(false);
  const conversations = useQuery({ queryKey: ["ai-conversations", page], queryFn: () => assistantApi.conversations(page), enabled: !DEMO_MODE });
  const history = useInfiniteQuery({ queryKey: ["ai-messages", selected], queryFn: ({ pageParam }) => assistantApi.messages(selected!, pageParam), enabled: !!selected && !DEMO_MODE, initialPageParam: 0,
    getNextPageParam: last => last.number + 1 < last.totalPages ? last.number + 1 : undefined });
  const messages = history.data?.pages.flatMap(result => result.content).slice().reverse() ?? [];
  const chat = useMutation({
    mutationFn: ({ request, signal }: { request: ChatRequest; signal: AbortSignal }) => assistantApi.chat(request, signal),
    onSuccess: async (response, { request }) => {
      if (activeRequest.current === request.requestId) {
        setSelected(response.conversationId); setPending(null); setNotice(""); setLastAnswer(response); setPage(0);
      }
      await Promise.all([client.invalidateQueries({ queryKey: ["ai-conversations"] }), client.invalidateQueries({ queryKey: ["ai-messages", response.conversationId] })]);
    },
    onError: (error, { request }) => {
      if (activeRequest.current === request.requestId) setNotice(cancelled.current ? "Stopped waiting for this answer. The server may still finish. Refresh saved messages or retry this same request to recover the answer without sending it twice." : "status" in error && error.status === 409 ? `${error.message} Refresh saved messages or retry shortly using the same question; no second request will be created.` : error.message);
    }
  });
  const remove = useMutation({ mutationFn: assistantApi.remove, onSuccess: async (_response, id) => {
    if (selected === id) choose(null);
    setDeleting(null); client.removeQueries({ queryKey: ["ai-messages", id] });
    await client.invalidateQueries({ queryKey: ["ai-conversations"] });
  } });
  useEffect(() => () => controller.current?.abort(), []);

  function choose(id: string | null) {
    controller.current?.abort(); activeRequest.current = null; setSelected(id); setPending(null); setMessage(""); setNotice(""); setLastAnswer(null); chat.reset();
  }
  function send(request: ChatRequest) {
    cancelled.current = false; controller.current = new AbortController(); activeRequest.current = request.requestId;
    setPending(request); setNotice(""); chat.mutate({ request, signal: controller.current.signal });
  }
  function ask(text: string) {
    if (!text.trim() || chat.isPending) return;
    setMessage(""); send({ conversationId: selected ?? undefined, message: text.trim(), requestId: newOperationId() });
  }
  function submit(event: FormEvent) { event.preventDefault(); ask(message); }
  async function refreshHistory() { await Promise.all([conversations.refetch(), ...(selected ? [history.refetch()] : [])]); }

  return <AppShell title="AI Assistant"><div className="grid items-start gap-5 xl:grid-cols-[280px_minmax(0,1fr)]">
    <aside className="space-y-4"><PrimaryButton type="button" className="w-full gap-2" onClick={() => choose(null)}><Plus size={17} />New conversation</PrimaryButton><Panel title="Saved conversations">
      {DEMO_MODE ? <p className="text-sm text-ink/60">Saved conversation history requires a connected workspace. Demo replies are illustrative.</p> : <><QueryFeedback queries={[conversations]} /><div className="space-y-2">{conversations.data?.content.map(conversation => <div key={conversation.id} className={`flex items-start gap-2 rounded-lg border p-2 ${selected === conversation.id ? "border-moss bg-mint/40" : "border-ink/10"}`}><button type="button" className="focus-ring min-w-0 flex-1 rounded p-1 text-left text-sm" onClick={() => choose(conversation.id)}><span className="block break-words font-medium">{conversation.title || "Untitled conversation"}</span><span className="mt-1 block text-xs text-ink/50">{new Date(conversation.updatedAt).toLocaleString()}</span></button><button type="button" className="focus-ring rounded p-2 text-ink/45 hover:text-coral" aria-label={`Delete ${conversation.title || "conversation"}`} onClick={() => { setDeleting(conversation); remove.reset(); }}><Trash2 size={15} /></button></div>)}</div>{conversations.data && !conversations.data.content.length ? <p className="py-4 text-sm text-ink/55">Your saved conversations will appear here after your first question.</p> : null}<Pagination data={conversations.data} page={page} onChange={setPage} /></>}
    </Panel><Panel title="Try a question"><div className="space-y-2">{prompts.map(prompt => <button key={prompt} type="button" disabled={chat.isPending} className="focus-ring w-full rounded-lg border border-ink/10 p-3 text-left text-xs leading-5 hover:bg-mint/30 disabled:opacity-50" onClick={() => ask(prompt)}>{prompt}</button>)}</div></Panel></aside>
    <section className="min-w-0 space-y-4" aria-label="Business conversation"><Panel title={selected ? "Continue your conversation" : "Ask about your business"}>
      <p className="mb-5 text-xs leading-6 text-ink/55">Answers use the business data your role can access. Check each source’s period and limitations before acting. When no AI provider is configured, StockPilot uses its local analysis fallback.</p>
      {selected ? <QueryFeedback queries={[history]} /> : null}
      {history.hasNextPage ? <SecondaryButton type="button" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>{history.isFetchingNextPage ? "Loading…" : "Load older messages"}</SecondaryButton> : null}
      <div className="min-h-80 space-y-4 py-5" aria-label="Messages">
        {!messages.length && !pending && !lastAnswer && !history.isLoading ? <div className="py-12 text-center text-ink/50"><MessageSquare className="mx-auto mb-3 text-moss/60" size={30} /><p className="text-sm">Start with a question, then ask a follow-up in the same conversation.</p></div> : null}
        {messages.map(item => <article key={item.id} className={`rounded-xl p-4 ${item.role.toLowerCase() === "user" ? "ml-4 bg-moss text-white sm:ml-12" : "border border-ink/10 bg-white"}`} aria-label={item.role.toLowerCase() === "user" ? "Your message" : "Assistant answer"}><div className="mb-2 flex flex-wrap items-center gap-2 text-[11px] opacity-65"><strong>{item.role.toLowerCase() === "user" ? "You" : "StockPilot"}</strong><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString()}</time>{item.status === "FALLBACK" ? <span>Local fallback</span> : null}</div><p className="whitespace-pre-wrap break-words text-sm leading-7">{item.restricted ? "This message is hidden because your current role no longer has access to its source data." : item.content}</p>{!item.restricted ? <AssistantEvidence evidence={item.evidence} /> : null}{!item.restricted && item.role.toLowerCase() === "user" && item.requestId && (item.status === "RUNNING" || item.status === "FAILED") ? <div className="mt-3 space-y-2 border-t border-white/20 pt-3 text-xs leading-6"><p>{item.status === "RUNNING" ? "This saved question may still be processing. Refresh for its answer or retry shortly to recover the same request." : "This saved question did not finish. Retry to recover the answer using its original request."}</p><div className="flex flex-wrap gap-4"><button type="button" className="focus-ring rounded font-semibold underline" disabled={chat.isPending} onClick={() => send({ conversationId: selected!, message: item.content, requestId: item.requestId! })}>Recover saved question</button><button type="button" className="focus-ring rounded underline" onClick={() => void refreshHistory()}>Refresh this conversation</button></div></div> : null}</article>)}
        {lastAnswer && selected === lastAnswer.conversationId && !messages.some(item => item.id === lastAnswer.messageId) ? <article aria-label="Assistant answer" className="rounded-xl border border-ink/10 bg-white p-4"><p className="mb-2 text-xs text-ink/50">StockPilot · {lastAnswer.status === "FALLBACK" ? "Local fallback" : "Saved answer"}</p><p className="whitespace-pre-wrap text-sm leading-7">{lastAnswer.response}</p><AssistantEvidence evidence={lastAnswer.evidence} />{lastAnswer.limitations?.map((warning, index) => <p className="mt-2 text-xs text-amber-900" key={index}>{warning}</p>)}</article> : null}
        {pending && !messages.some(item => item.requestId === pending.requestId && item.role.toLowerCase() === "user") ? <div className="ml-4 rounded-xl bg-moss p-4 text-sm text-white sm:ml-12">{pending.message}</div> : null}
        {chat.isPending ? <div role="status" className="rounded-xl border border-moss/15 bg-mint/20 p-4"><LoadingState compact title="Checking your business data…" description="Gathering permitted records and preparing an answer. This can take a moment." /><button type="button" className="focus-ring mt-3 inline-flex items-center gap-2 rounded text-xs font-semibold text-moss underline" onClick={() => { cancelled.current = true; controller.current?.abort(); }}><Square size={12} />Stop waiting</button></div> : null}
        {notice ? <div role="alert" className="space-y-3 rounded-lg border border-amber/30 bg-amber/10 p-4"><p className="text-sm leading-6">{notice}</p><div className="flex flex-wrap gap-4 text-sm">{pending ? <button type="button" className="font-semibold text-moss underline" disabled={chat.isPending} onClick={() => send(pending)}>Retry same question</button> : null}<button type="button" className="text-moss underline" onClick={() => void refreshHistory()}>Refresh saved messages</button></div></div> : null}
      </div>
      <form onSubmit={submit} className="space-y-3 border-t border-ink/10 pt-5"><Field label="Your question"><textarea className="focus-ring min-h-24 w-full rounded-lg border border-ink/15 p-3 text-sm" maxLength={4000} value={message} onChange={event => setMessage(event.target.value)} placeholder="Ask about stock, sales, outstanding balances, or a previous answer…" disabled={chat.isPending} /></Field><div className="flex items-center justify-between gap-3"><span className="text-xs text-ink/45">{message.length}/4,000 characters</span><PrimaryButton className="gap-2" type="submit" disabled={chat.isPending || !message.trim()}><Send size={16} />{chat.isPending ? "Preparing answer…" : selected ? "Send follow-up" : "Ask StockPilot"}</PrimaryButton></div></form>
    </Panel></section>
    {deleting ? <div className="fixed inset-0 z-40 flex items-center justify-center bg-ink/40 p-5"><section role="dialog" aria-modal="true" aria-labelledby="delete-chat-title" className="w-full max-w-md rounded-xl bg-white p-6 shadow-soft"><h2 id="delete-chat-title" className="text-xl font-semibold">Delete this conversation?</h2><p className="my-4 text-sm leading-6 text-ink/65">This removes “{deleting.title || "Untitled conversation"}” and its saved messages. It does not change your business records.</p><MutationFeedback mutation={remove} /><div className="mt-5 flex gap-3"><PrimaryButton type="button" disabled={remove.isPending} onClick={() => remove.mutate(deleting.id)}>{remove.isPending ? "Deleting…" : "Delete conversation"}</PrimaryButton><SecondaryButton type="button" disabled={remove.isPending} onClick={() => setDeleting(null)}>Cancel</SecondaryButton></div></section></div> : null}
  </div></AppShell>;
}
