import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Plus } from "lucide-react"
import { toast } from "sonner"

import { api } from "@/lib/api"
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

export function NewSystemDialog() {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({ id: "", title: "", kind: "system", description: "", owner: "" })

  const create = useMutation({
    mutationFn: () => api.addSystem({ ...form, description: form.description || undefined }),
    onSuccess: () => {
      toast.success(`Система «${form.id}» заведена`)
      qc.invalidateQueries({ queryKey: ["systems"] })
      setOpen(false)
      setForm({ id: "", title: "", kind: "system", description: "", owner: "" })
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })

  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }))

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">
          <Plus /> Система
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Новая система</DialogTitle>
          <DialogDescription>
            L0-группировка: внутри живут контейнеры и их БД. Владелец уходит в CODEOWNERS.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="sys-id">id (например, billing)</Label>
            <Input id="sys-id" value={form.id} onChange={(e) => set("id")(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="sys-title">Название</Label>
            <Input id="sys-title" value={form.title} onChange={(e) => set("title")(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label>Круг ответственности</Label>
            <Select value={form.kind} onValueChange={set("kind")}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="system">system — наш продукт</SelectItem>
                <SelectItem value="orgSystem">orgSystem — другая команда</SelectItem>
                <SelectItem value="externalSystem">externalSystem — вне компании</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="sys-owner">Владелец (@org/team)</Label>
            <Input id="sys-owner" value={form.owner} onChange={(e) => set("owner")(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="sys-desc">Описание (необязательно)</Label>
            <Input id="sys-desc" value={form.description} onChange={(e) => set("description")(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button disabled={!form.id || !form.title || !form.owner || create.isPending} onClick={() => create.mutate()}>
            Завести
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
