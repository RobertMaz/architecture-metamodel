import { useParams, Link } from "react-router"
import { useQuery } from "@tanstack/react-query"
import { ArrowLeft } from "lucide-react"

import { api } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { AnalyzeButton } from "@/components/ContainersTable"
import { ReportView } from "@/components/ReportView"
import { DiffView } from "@/components/DiffView"
import { SourcesCard } from "@/components/SourcesCard"

export function Container() {
  const { id = "" } = useParams()

  const containers = useQuery({
    queryKey: ["containers"],
    queryFn: api.containers,
    refetchInterval: (q) =>
      q.state.data?.some((c) => c.state === "running" || c.state === "queued") ? 1000 : false,
  })
  const container = containers.data?.find((c) => c.id === id)

  const report = useQuery({
    queryKey: ["report", id, container?.state],
    queryFn: () => api.report(id),
    enabled: !!container?.analyzed,
  })

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" asChild>
          <Link to="/">
            <ArrowLeft />
          </Link>
        </Button>
        <div className="flex-1">
          <h1 className="font-mono text-xl font-semibold">{id}</h1>
          {container && (
            <p className="text-sm text-muted-foreground">
              {container.repo} · evidence: {container.evidenceLanes.join(", ") || "—"}
              {container.lanes.length > 0 && ` · последний прогон: ${container.lanes.join(", ")}`}
            </p>
          )}
        </div>
        {container?.state === "running" && <Badge variant="secondary">анализ…</Badge>}
        {container?.state === "failed" && <Badge variant="destructive">ошибка</Badge>}
        {container && <AnalyzeButton container={container} />}
      </div>

      {!container && !containers.isLoading && (
        <Card>
          <CardContent className="py-8 text-center text-muted-foreground">
            Контейнер «{id}» не найден в реестре.
          </CardContent>
        </Card>
      )}

      {container && !container.analyzed && container.state !== "running" && (
        <Card>
          <CardContent className="py-8 text-center text-muted-foreground">
            Ещё не анализировался — жми «Анализ».
          </CardContent>
        </Card>
      )}

      {container && <SourcesCard container={container} />}

      {report.data && <ReportView report={report.data} />}

      <Card>
        <CardHeader>
          <CardTitle>Дифф прогона</CardTitle>
          <CardDescription>
            Что изменилось в tools/api-source, model/gen и registry с последнего коммита. Коммитишь сам.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DiffView />
        </CardContent>
      </Card>
    </div>
  )
}
