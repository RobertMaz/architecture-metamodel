import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Plus } from "lucide-react"
import { toast } from "sonner"

import { api, type SystemInfo } from "@/lib/api"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

export function NewContainerDialog({ systems }: { systems: SystemInfo[] }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [system, setSystem] = useState("")
  const [name, setName] = useState("")
  const [repo, setRepo] = useState("")
  const [path, setPath] = useState("")
  const [jar, setJar] = useState("")
  const [runtimeUrl, setRuntimeUrl] = useState("")
  const [traces, setTraces] = useState("")
  const [openapi, setOpenapi] = useState("")

  const id = system && name ? `${system}.${name}` : ""

  const create = useMutation({
    mutationFn: () =>
      api.addContainer({
        id,
        repo,
        path,
        jar: jar || undefined,
        runtimeUrl: runtimeUrl || undefined,
        traces: traces || undefined,
        openapi: openapi || undefined,
      }),
    onSuccess: () => {
      toast.success(`Контейнер «${id}» добавлен`)
      qc.invalidateQueries({ queryKey: ["containers"] })
      setOpen(false)
      setName("")
      setRepo("")
      setPath("")
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>
          <Plus /> Контейнер
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Новый контейнер</DialogTitle>
          <DialogDescription>
            Выбор системы — ранний и осознанный: id иерархичен ({id || "система.имя"}), перенос потом — операция
            rename. Обязательны только сорцы; JAR, запущенная апка и трейсы добирают точность.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label>Система</Label>
            <Select value={system} onValueChange={setSystem}>
              <SelectTrigger>
                <SelectValue placeholder="Выбери систему" />
              </SelectTrigger>
              <SelectContent>
                {systems.map((s) => (
                  <SelectItem key={s.id} value={s.id}>
                    {s.id} — {s.title}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-name">Имя (например, orders)</Label>
            <Input id="c-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-repo">URL репозитория</Label>
            <Input id="c-repo" value={repo} onChange={(e) => setRepo(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-path">Локальный путь к сорцам</Label>
            <Input id="c-path" value={path} onChange={(e) => setPath(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-jar">JAR из Nexus/сборки (необязательно — полка bytecode)</Label>
            <Input id="c-jar" value={jar} onChange={(e) => setJar(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-rt">URL запущенной апки (необязательно — полка runtime, Actuator)</Label>
            <Input id="c-rt" placeholder="http://localhost:8080" value={runtimeUrl} onChange={(e) => setRuntimeUrl(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-tr">Файл OTel-спанов (необязательно — полка traces)</Label>
            <Input id="c-tr" value={traces} onChange={(e) => setTraces(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="c-oa">OpenAPI-спека (необязательно — иначе автопоиск openapi.yml в репо)</Label>
            <Input id="c-oa" value={openapi} onChange={(e) => setOpenapi(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button disabled={!id || !path || create.isPending} onClick={() => create.mutate()}>
            Добавить
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
