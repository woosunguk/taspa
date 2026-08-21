import type { Metadata } from "next";
import type { ReactNode } from "react";
import { AdminShell } from "./_components/AdminShell";

export const metadata: Metadata = {
  title: "플랫폼 관리",
};

export default function AdminLayout({ children }: { children: ReactNode }) {
  return <AdminShell>{children}</AdminShell>;
}
