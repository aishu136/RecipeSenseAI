"use client";

import { usePathname } from "next/navigation";
import { useEffect, useRef } from "react";
import { linkButtonClass } from "@/components/ui";
import { signInHref } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { closeSignInDialog, useSignInDialog } from "@/lib/signInDialog";

// The popup asking the user to sign in, e.g. after their free recipes
export function SignInDialog() {
  const message = useSignInDialog();
  const pathname = usePathname();
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);

  // A modal <dialog> traps focus and closes on Escape by itself
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (message && !element.open) element.showModal();
    if (!message && element.open) element.close();
  }, [message]);

  return (
    <dialog
      ref={dialog}
      onClose={closeSignInDialog}
      aria-labelledby="sign-in-title"
      className="m-auto w-[calc(100%-2rem)] max-w-sm rounded-2xl border border-border bg-surface p-6 text-foreground shadow-xl backdrop:bg-black/40"
    >
      <h2 id="sign-in-title" className="text-lg font-semibold">
        {t("dialog.signInTitle")}
      </h2>
      <p className="mt-2 text-sm text-muted">{message}</p>
      <div className="mt-5 flex justify-end gap-2">
        <button
          type="button"
          onClick={closeSignInDialog}
          className="rounded-lg px-4 py-2 text-sm font-medium text-muted transition hover:bg-surface-2 hover:text-foreground"
        >
          {t("dialog.notNow")}
        </button>
        {/* A plain link: /auth/login is a route handler that redirects to the OIDC provider */}
        <a href={signInHref(pathname)} className={linkButtonClass} autoFocus>
          {t("nav.signIn")}
        </a>
      </div>
    </dialog>
  );
}
