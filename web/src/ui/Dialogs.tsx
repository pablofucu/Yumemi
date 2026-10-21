import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'

function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  )
}

export function RenameDialog({ initial, onSave, onClose }: { initial: string; onSave: (title: string) => void; onClose: () => void }) {
  const [value, setValue] = useState(initial)
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    ref.current?.focus()
    ref.current?.select()
  }, [])
  const submit = () => {
    if (value.trim() !== '') {
      onSave(value.trim())
      onClose()
    }
  }
  return (
    <Modal title="Renombrar conversación" onClose={onClose}>
      <input
        ref={ref}
        className="field"
        value={value}
        maxLength={120}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
      />
      <div className="modal-actions">
        <button type="button" className="btn text" onClick={onClose}>
          Cancelar
        </button>
        <button type="button" className="btn primary" onClick={submit} disabled={value.trim() === ''}>
          Guardar
        </button>
      </div>
    </Modal>
  )
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  danger,
  onConfirm,
  onClose,
}: {
  title: string
  message: string
  confirmLabel: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Modal title={title} onClose={onClose}>
      <p className="muted">{message}</p>
      <div className="modal-actions">
        <button type="button" className="btn text" onClick={onClose}>
          Cancelar
        </button>
        <button
          type="button"
          className={danger ? 'btn danger' : 'btn primary'}
          onClick={() => {
            onConfirm()
            onClose()
          }}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  )
}
