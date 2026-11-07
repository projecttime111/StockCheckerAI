import { ReactNode } from "react";
import { PlatformShell } from "@/components/platform/PlatformShell";

export default function AdminLayout({ children }: { children: ReactNode }) {
  return <PlatformShell>{children}</PlatformShell>;
}
