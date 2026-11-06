"use client";

import { useMutation } from "@tanstack/react-query";
import { Send } from "lucide-react";
import { FormEvent, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { PrimaryButton } from "@/components/FormControls";
import { ErrorState } from "@/components/ErrorState";
import { Panel } from "@/components/Panel";
import { api } from "@/lib/api";

type ChatResponse = { conversationId: string; response: string; evidence: Record<string, unknown> };

const prompts = [
  "What should I reorder this week?",
  "Which products will stock out soon?",
  "Which products are dead stock?",
  "Why did my profit drop this month?",
  "Which customer has the highest outstanding?",
  "Which supplier increased cost the most?",
  "Which product has the best margin?",
  "Which warehouse has stock mismatch risk?",
  "Which imported products need cleanup?"
];

export default function AssistantPage() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [message, setMessage] = useState("Why did my profit drop this month?");
  const [messages, setMessages] = useState<{ role: "user" | "assistant"; content: string }[]>([]);
  const chat = useMutation({
    mutationFn: (text: string) => api<ChatResponse>("/api/ai/assistant/chat", { method: "POST", body: JSON.stringify({ conversationId, message: text }) }),
    onSuccess: (data) => {
      setConversationId(data.conversationId);
      setMessages((current) => [...current, { role: "assistant", content: data.response }]);
    }
  });

  function ask(text: string) {
    if (chat.isPending) return;
    setMessages((current) => [...current, { role: "user", content: text }]);
    chat.mutate(text);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (message.trim()) {
      ask(message.trim());
      setMessage("");
    }
  }

  return (
    <AppShell title="AI Assistant">
      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Panel title="Business Chat">
          <div className="mb-4 min-h-[420px] space-y-3 rounded bg-ink/[0.03] p-4">
            {messages.length === 0 ? <p className="text-sm text-ink/55">Ask a question grounded in your tenant data. StockPilot AI analyzes inventory, profit, import quality, reorder, and dead-stock data exported from Tally, Excel, or your ERP.</p> : messages.map((item, index) => (
              <div key={index} className={item.role === "user" ? "ml-auto max-w-2xl rounded bg-moss px-4 py-3 text-sm text-white" : "max-w-2xl rounded border border-ink/10 bg-white px-4 py-3 text-sm text-ink"}>
                {item.content}
              </div>
            ))}
            {chat.isPending ? <div className="max-w-md rounded border border-ink/10 bg-white px-4 py-3 text-sm text-ink/55">Thinking with your data...</div> : null}
          </div>
          {chat.error ? <ErrorState error={chat.error} /> : null}
          <form onSubmit={submit} className="flex gap-2">
            <input aria-label="Message" className="focus-ring h-11 flex-1 rounded border border-ink/15 px-3 text-sm" value={message} onChange={(e) => setMessage(e.target.value)} />
            <PrimaryButton aria-label="Send message" disabled={chat.isPending}><Send size={16} /></PrimaryButton>
          </form>
        </Panel>
        <Panel title="Suggested Questions">
          <div className="space-y-2">
            {prompts.map((prompt) => <button disabled={chat.isPending} key={prompt} className="focus-ring w-full rounded border border-ink/10 p-3 text-left text-sm hover:bg-ink/[0.03]" onClick={() => ask(prompt)}>{prompt}</button>)}
          </div>
        </Panel>
      </div>
    </AppShell>
  );
}
