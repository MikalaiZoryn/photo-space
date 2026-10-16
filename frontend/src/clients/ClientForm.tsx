import { useState, type FormEvent } from 'react'
import { createClient, updateClient, type Client, type ClientInput } from '../api/clients.ts'
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
    <form onSubmit={submit} aria-label={client ? 'Edit client' : 'New client'} className="stack">
      <label>
        Name
        <input required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      {error.fields.name && <p role="alert">Name {error.fields.name}</p>}
      <label>
        Email
        <input type="email" maxLength={320} value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      {error.fields.email && <p role="alert">Email {error.fields.email}</p>}
      <label>
        Phone
        <input type="tel" maxLength={50} value={phone} onChange={(e) => setPhone(e.target.value)} />
      </label>
      {error.fields.phone && <p role="alert">Phone {error.fields.phone}</p>}
      <label>
        Notes
        <textarea rows={4} maxLength={10000} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      {error.fields.notes && <p role="alert">Notes {error.fields.notes}</p>}
      {error.message && <p role="alert">{error.message}</p>}
      <div>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : client ? 'Save client' : 'Create client'}
        </button>{' '}
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
