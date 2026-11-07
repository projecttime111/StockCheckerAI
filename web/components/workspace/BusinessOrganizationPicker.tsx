"use client";

import { OrganizationList } from "@clerk/nextjs";
import { useEffect, useState } from "react";
import { LoadingState } from "@/components/LoadingState";

export function BusinessOrganizationPicker() {
  return <OrganizationList hidePersonal skipInvitationScreen afterSelectOrganizationUrl="/auth/complete" afterCreateOrganizationUrl="/onboarding" fallback={<OrganizationPickerLoading />} />;
}

function OrganizationPickerLoading() {
  const [delayed, setDelayed] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setDelayed(true), 15_000);
    return () => window.clearTimeout(timer);
  }, []);
  return <div className="space-y-3">
    <LoadingState compact title="Loading your business organizations…" description={delayed ? "This is taking longer than expected. Check your connection and reload to try again." : "You can select a business or create a new one here."} />
    {delayed ? <button type="button" className="text-sm font-semibold text-moss underline" onClick={() => window.location.reload()}>Reload organizations</button> : null}
  </div>;
}
