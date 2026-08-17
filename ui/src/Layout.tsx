import { Link, Outlet } from "react-router"
import { Network } from "lucide-react"

import { Toaster } from "@/components/ui/sonner"
import { Button } from "@/components/ui/button"

export function Layout() {
  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <Network className="size-5" />
            arch-analyzer
          </Link>
          <div className="ml-auto">
            <Button variant="outline" size="sm" asChild>
              <a href="http://localhost:5173" target="_blank" rel="noreferrer">
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
