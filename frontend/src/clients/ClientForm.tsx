import { useState, type FormEvent } from 'react'
import { createClient, updateClient, type Client, type ClientInput } from '../api/clients.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { ErrorText, Field, formCard, FormActions } from '../ui/Field.tsx'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'

type Props = {
  workspaceId: string
  /** The client to edit; omitted to create one. */
  client?: Client
  onSaved: (client: Client) => void
  onCancel: () => void
}

export function ClientForm({ workspaceId, client, onSaved, onCancel }: Props) {
  const [name, setName] = useState(client?.name ?? '')
  const [email, setEmail] = useState(client?.email ?? '')
  const [phone, setPhone] = useState(client?.phone ?? '')
  const [notes, setNotes] = useState(client?.notes ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(noFormError)
    const input: ClientInput = {
      name: name.trim(),
      email: email.trim() || null,
      phone: phone.trim() || null,
      notes: notes.trim() || null,
    }
    try {
      onSaved(
        client
          ? await updateClient(workspaceId, client.id, client.version, input)
          : await createClient(workspaceId, input),
      )
    } catch (e) {
      setError(toFormError(e, client ? 'save the client' : 'create the client'))
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={submit}
      aria-label={client ? 'Edit client' : 'New client'}
      className={formCard}
    >
      <Field label="Name" className="md:col-span-2" error={error.fields.name && `Name ${error.fields.name}`}>
        <Input required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Email" error={error.fields.email && `Email ${error.fields.email}`}>
        <Input type="email" maxLength={320} value={email} onChange={(e) => setEmail(e.target.value)} />
      </Field>
      <Field label="Phone" error={error.fields.phone && `Phone ${error.fields.phone}`}>
        <Input type="tel" maxLength={50} value={phone} onChange={(e) => setPhone(e.target.value)} />
      </Field>
      <Field label="Notes" className="md:col-span-2" error={error.fields.notes && `Notes ${error.fields.notes}`}>
        <Textarea rows={4} maxLength={10000} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </Field>
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <FormActions>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : client ? 'Save client' : 'Create client'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </FormActions>
    </form>
  )
}
