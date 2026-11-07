import { LoadingState } from "@/components/LoadingState";

export default function Loading() {
  return <main className="mx-auto flex min-h-[60vh] max-w-lg items-center px-5"><div className="w-full"><LoadingState title="Opening StockPilot…" description="Getting the next page ready." /></div></main>;
}
