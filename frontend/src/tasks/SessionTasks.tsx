import { Plus } from 'lucide-react'
import { paths } from '../routing.ts'
import { Button } from '@/components/ui/button'
import { Panel } from '../ui/Panel.tsx'
import { TaskList } from './TaskList.tsx'

type Props = { workspaceId: string; sessionId: string; archived: boolean }

/** The session's tasks, open ones first, including those its automation rules created. */
export function SessionTasks({ workspaceId, sessionId, archived }: Props) {
  return (
    <Panel
      id="session-tasks-heading"
      title="Tasks"
      actions={
        !archived && (
          <Button asChild variant="outline" size="sm">
            <a href={paths.taskNew(sessionId)}>
              <Plus aria-hidden="true" />
              Add task
            </a>
          </Button>
        )
      }
    >
      <TaskList
        workspaceId={workspaceId}
        filter={{ sessionId }}
        hideSession
        emptyText="No tasks for this session yet."
      />
    </Panel>
  )
}
