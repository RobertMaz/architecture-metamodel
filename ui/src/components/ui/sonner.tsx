import { Toaster as Sonner, type ToasterProps } from "sonner"

function Toaster(props: ToasterProps) {
  return <Sonner richColors position="bottom-right" {...props} />
}

export { Toaster }
