import { useState } from "react"
import { useNavigate } from "react-router"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { FolderInput, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { api, type ContainerInfo } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/**
 * Перенос контейнера в другую систему и удаление. Перенос — это смена id
 * (<система>.<имя>): сервер переносит реестры, api-source и улики, затем
 * регенерирует модель. Рукописные ссылки на старый id подсветит npm run check.
 */
export function ManageCard({ container }: { container: ContainerInfo }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [target, setTarget] = useState("")

  const systems = useQuery({ queryKey: ["systems"], queryFn: api.systems })
  const targets = (systems.data ?? []).filter((s) => s.id !== container.system)
  const busy = container.state === "running" || container.state === "queued"

  const move = useMutation({
    mutationFn: () => api.moveContainer(container.id, target),
    onSuccess: ({ moved }) => {
      toast.success(`Контейнер перенесён: ${moved}`)
      qc.invalidateQueries({ queryKey: ["containers"] })
      navigate(`/containers/${moved}`)
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })

  const del = useMutation({
    mutationFn: () => api.deleteContainer(container.id),
    onSuccess: () => {
      toast.success(`Контейнер ${container.id} удалён`)
      qc.invalidateQueries({ queryKey: ["containers"] })
      navigate("/")
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })

  const confirmDelete = () => {
    const ok = window.confirm(
      `Удалить ${container.id}? Уйдут запись в реестре, api-source-док и накопленные улики (workspace). Отменить можно только через git.`,
    )
    if (ok) del.mutate()
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Управление</CardTitle>
        <CardDescription>
          Перенос меняет id контейнера — рукописные ссылки на старый id подсветит npm run check.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3">
        <div className="grid gap-1.5">
          <Label>Перенести в систему</Label>
          <Select value={target} onValueChange={setTarget}>
            <SelectTrigger className="min-w-48">
              <SelectValue placeholder="Выбери систему" />
            </SelectTrigger>
            <SelectContent>
              {targets.map((s) => (
                <SelectItem key={s.id} value={s.id}>
                  {s.id} — {s.title}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <Button disabled={!target || busy || move.isPending} onClick={() => move.mutate()}>
          <FolderInput /> Перенести
        </Button>
        <div className="flex-1" />
        <Button variant="destructive" disabled={busy || del.isPending} onClick={confirmDelete}>
          <Trash2 /> Удалить контейнер
        </Button>
      </CardContent>
    </Card>
  )
}
