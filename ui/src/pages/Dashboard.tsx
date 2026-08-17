import { useQuery } from "@tanstack/react-query"
import { toast } from "sonner"

import { api } from "@/lib/api"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ContainersTable } from "@/components/ContainersTable"
import { NewContainerDialog } from "@/components/NewContainerDialog"
import { NewSystemDialog } from "@/components/NewSystemDialog"

export function Dashboard() {
  const containers = useQuery({
    queryKey: ["containers"],
    queryFn: api.containers,
    // Пока идёт хоть один анализ — поллим статусы.
    refetchInterval: (q) => (q.state.data?.some((c) => c.state === "running") ? 1000 : false),
  })
  const systems = useQuery({ queryKey: ["systems"], queryFn: api.systems })

  if (containers.error) toast.error(String(containers.error))

  const list = containers.data ?? []
  const analyzed = list.filter((c) => c.analyzed).length

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Инвентарь</h1>
          <p className="text-sm text-muted-foreground">
            {list.length} контейнеров, проанализировано {analyzed}; систем: {systems.data?.length ?? 0}
          </p>
        </div>
        <div className="flex gap-2">
          <NewSystemDialog />
          <NewContainerDialog systems={systems.data ?? []} />
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Контейнеры</CardTitle>
          <CardDescription>
            Прогон анализирует репозиторий, дообогащает модель и показывает дифф — коммитишь сам.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ContainersTable containers={list} loading={containers.isLoading} />
        </CardContent>
      </Card>
    </div>
  )
}
