"use client";

import { useParams } from "next/navigation";
import { BusinessDetailView } from "@/components/platform/BusinessDetailView";

export default function PlatformBusinessPage() {
  const { id } = useParams<{ id: string }>();
  return <BusinessDetailView id={id} />;
}
