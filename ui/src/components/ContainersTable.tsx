import { useMemo, useState } from "react"
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type SortingState,
} from "@tanstack/react-table"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router"
import { ArrowUpDown, Loader2, Play } from "lucide-react"
import { toast } from "sonner"

import { api, type ContainerInfo } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

function StateBadge({ state, lane, analyzed }: { state: ContainerInfo["state"]; lane?: string | null; analyzed: boolean }) {
  if (state === "queued") return <Badge variant="outline">в очереди</Badge>
  if (state === "running") return <Badge variant="secondary">{lane ? `анализ: ${lane}…` : "анализ…"}</Badge>
  if (state === "failed") return <Badge variant="destructive">ошибка</Badge>
  if (analyzed) return <Badge variant="success">готово</Badge>
  return <Badge variant="outline">не анализировался</Badge>
}

export function AnalyzeButton({ container }: { container: ContainerInfo }) {
  const qc = useQueryClient()
  const analyze = useMutation({
    mutationFn: () => api.analyze(container.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["containers"] }),
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })
  const running = container.state === "running" || container.state === "queued" || analyze.isPending
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={running}
      onClick={(e) => {
        e.preventDefault()
        analyze.mutate()
      }}
    >
      {running ? <Loader2 className="animate-spin" /> : <Play />}
      Анализ
    </Button>
  )
}

const col = createColumnHelper<ContainerInfo>()

export function ContainersTable({ containers, loading }: { containers: ContainerInfo[]; loading: boolean }) {
  const [sorting, setSorting] = useState<SortingState>([{ id: "id", desc: false }])

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const columns = useMemo<ColumnDef<ContainerInfo, any>[]>(
    () => [
      col.accessor("id", {
        header: "Контейнер",
        cell: (c) => (
          <Link className="font-medium hover:underline" to={`/containers/${c.getValue()}`}>
            {c.getValue()}
          </Link>
        ),
      }),
      col.accessor("system", { header: "Система" }),
      col.display({
        id: "state",
        header: "Статус",
        cell: (c) => <StateBadge state={c.row.original.state} lane={c.row.original.lane} analyzed={c.row.original.analyzed} />,
      }),
      col.accessor("evidenceLanes", {
        header: "Полки (evidence)",
        cell: (c) => {
          const evidence = c.getValue() as string[]
          const lastRun = c.row.original.lanes
          if (!evidence.length) return "—"
          // Полка с evidence, не отработавшая в последнем прогоне (источник недоступен), — приглушена
          return (
            <span>
              {evidence.map((l: string, i: number) => (
                <span key={l} className={lastRun.length > 0 && !lastRun.includes(l) ? "text-muted-foreground" : undefined}>
                  {i > 0 && ", "}
                  {l}
                </span>
              ))}
            </span>
          )
        },
      }),
      col.accessor("operations", { header: "Операции" }),
      col.accessor("calls", { header: "Вызовы" }),
      col.accessor("stores", { header: "Сторы" }),
      col.accessor("unresolvedCalls", {
        header: "Без разрешения",
        cell: (c) => (c.getValue() > 0 ? <Badge variant="secondary">{c.getValue()}</Badge> : "0"),
      }),
      col.display({
        id: "actions",
        header: "",
        cell: (c) => <AnalyzeButton container={c.row.original} />,
      }),
    ],
    [],
  )

  const table = useReactTable({
    data: containers,
    columns,
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  })

  if (loading) return <div className="py-8 text-center text-muted-foreground">Загрузка…</div>
  if (!containers.length)
    return (
      <div className="py-8 text-center text-muted-foreground">
        Пусто. Добавь систему и контейнер — и запускай анализ.
      </div>
    )

  return (
    <Table>
      <TableHeader>
        {table.getHeaderGroups().map((hg) => (
          <TableRow key={hg.id}>
            {hg.headers.map((h) => (
              <TableHead key={h.id}>
                {h.column.getCanSort() ? (
                  <button
                    className="inline-flex items-center gap-1 hover:text-foreground"
                    onClick={h.column.getToggleSortingHandler()}
                  >
                    {flexRender(h.column.columnDef.header, h.getContext())}
                    <ArrowUpDown className="size-3" />
                  </button>
                ) : (
                  flexRender(h.column.columnDef.header, h.getContext())
                )}
              </TableHead>
            ))}
          </TableRow>
        ))}
      </TableHeader>
      <TableBody>
        {table.getRowModel().rows.map((row) => (
          <TableRow key={row.id}>
            {row.getVisibleCells().map((cell) => (
              <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
