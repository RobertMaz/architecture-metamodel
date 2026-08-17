import { useQuery } from "@tanstack/react-query"
import { RefreshCw } from "lucide-react"

import { api } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"

const statusLabel: Record<string, { text: string; variant: "success" | "secondary" | "destructive" }> = {
  new: { text: "новый", variant: "success" },
  modified: { text: "изменён", variant: "secondary" },
  deleted: { text: "удалён", variant: "destructive" },
}

export function DiffView() {
  const diff = useQuery({ queryKey: ["diff"], queryFn: api.diff })

  if (diff.isLoading) return <div className="text-muted-foreground">Загрузка…</div>
  if (!diff.data) return null

  const { files, patch } = diff.data

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={() => diff.refetch()}>
          <RefreshCw /> Обновить
        </Button>
        {!files.length && <span className="text-muted-foreground">Рабочее дерево чистое — модель совпадает с git.</span>}
      </div>

      {files.length > 0 && (
        <ul className="space-y-1 text-sm">
          {files.map((f) => (
            <li key={f.path} className="flex items-center gap-2">
              <Badge variant={statusLabel[f.status]?.variant ?? "secondary"}>
                {statusLabel[f.status]?.text ?? f.status}
              </Badge>
              <span className="font-mono text-xs">{f.path}</span>
            </li>
          ))}
        </ul>
      )}

      {patch && (
        <pre className="max-h-96 overflow-auto rounded-md border bg-muted/50 p-3 text-xs leading-5">
          {patch.split("\n").map((line, i) => (
            <div
              key={i}
              className={
                line.startsWith("+") && !line.startsWith("+++")
                  ? "text-emerald-600 dark:text-emerald-400"
                  : line.startsWith("-") && !line.startsWith("---")
                    ? "text-red-600 dark:text-red-400"
                    : line.startsWith("@@")
                      ? "text-sky-600 dark:text-sky-400"
                      : undefined
              }
            >
              {line || " "}
            </div>
          ))}
        </pre>
      )}
    </div>
  )
}
