import { redirect } from "next/navigation";
import { CLERK_ENABLED } from "@/lib/api";

// Keep old bookmarks usable while account migration is outside the product flow.
export default function AccountLinkPage() {
  redirect(CLERK_ENABLED ? "/auth/complete" : "/login");
}
