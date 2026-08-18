import { Link, Outlet } from "react-router"
import { useQuery } from "@tanstack/react-query"
import { Network, SearchX } from "lucide-react"

import { api } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Toaster } from "@/components/ui/sonner"
import { Button } from "@/components/ui/button"

export function Layout() {
  const unresolved = useQuery({ queryKey: ["unresolved"], queryFn: api.unresolved })
  const openCount = unresolved.data?.unresolved.filter((e) => e.stubId).length ?? 0

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <Network className="size-5" />
            arch-analyzer
          </Link>
          <Link to="/triage" className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
            <SearchX className="size-4" />
            Триаж
            {openCount > 0 && <Badge variant="secondary">{openCount}</Badge>}
          </Link>
          <div className="ml-auto">
            <Button variant="outline" size="sm" asChild>
              <a href={import.meta.env.VITE_LIKEC4_URL ?? "http://localhost:5173"} target="_blank" rel="noreferrer">
                Открыть LikeC4
              </a>
            </Button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
      <Toaster />
    </div>
  )
}
