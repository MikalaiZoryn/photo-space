import { useState, type FormEvent } from 'react'
import { createRule, TRIGGER_STATUSES, updateRule, type Rule, type RuleInput, type TriggerStatus } from '../api/automation.ts'
import { statusLabels } from '../sessions/status.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { CheckboxField, ErrorText, Field, FormActions } from '../ui/Field.tsx'

type Props = {
  workspaceId: string
  /** The rule to edit; omitted to create one. */
  rule?: Rule
  onSaved: (rule: Rule) => void
  onCancel: () => void
}

export function RuleForm({ workspaceId, rule, onSaved, onCancel }: Props) {
  const [triggerStatus, setTriggerStatus] = useState<TriggerStatus>(rule?.triggerStatus ?? 'COMPLETED')
  const [taskTitle, setTaskTitle] = useState(rule?.taskTitle ?? '')
  const [dueInDays, setDueInDays] = useState(String(rule?.dueInDays ?? 3))
  const [enabled, setEnabled] = useState(rule?.enabled ?? true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(noFormError)
    const input: RuleInput = { triggerStatus, taskTitle: taskTitle.trim(), dueInDays: Number(dueInDays), enabled }
    try {
      onSaved(rule ? await updateRule(workspaceId, rule.id, rule.version, input) : await createRule(workspaceId, input))
    } catch (e) {
      setError(toFormError(e, rule ? 'save the rule' : 'create the rule'))
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={submit}
      aria-label={rule ? 'Edit rule' : 'New rule'}
      className="grid gap-4 rounded-lg border bg-muted/30 p-4 md:grid-cols-2"
    >
      <Field label="When a session is marked" error={error.fields.triggerStatus && `Status ${error.fields.triggerStatus}`}>
        <NativeSelect className="w-full" value={triggerStatus} onChange={(e) => setTriggerStatus(e.target.value as TriggerStatus)}>
          {TRIGGER_STATUSES.map((s) => (
            <NativeSelectOption key={s} value={s}>
              {statusLabels[s]}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>
      <Field label="Due after (days)" error={error.fields.dueInDays && `Days ${error.fields.dueInDays}`}>
        <Input type="number" required min={0} max={365} value={dueInDays} onChange={(e) => setDueInDays(e.target.value)} />
      </Field>
      <Field label="Create a task titled" className="md:col-span-2" error={error.fields.taskTitle && `Title ${error.fields.taskTitle}`}>
        <Input required maxLength={200} value={taskTitle} onChange={(e) => setTaskTitle(e.target.value)} />
      </Field>
      <div className="md:col-span-2">
        <CheckboxField label="Enabled" checked={enabled} onChange={setEnabled} />
      </div>
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <FormActions>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : rule ? 'Save rule' : 'Create rule'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </FormActions>
    </form>
  )
}
