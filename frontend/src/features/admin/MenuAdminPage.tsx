import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { type MenuGroupDto, adminConsole, adminConsoleKeys } from '@/api/adminConsole'
import { DEFAULT_MENU, REQUIRED_PATHS, SCREENS } from '@/components/navMenu'
import { Alert, ErrorMessage } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input, Select } from '@/components/ui/input'
import { TBody, TD, TH, THead, TR, Table } from '@/components/ui/table'

/** 메뉴 관리 (R70): 대분류·메뉴 추가·삭제·이름·순서·이동. 저장 전까지는 이 화면에서만 바뀐다 */
export function MenuAdminPage() {
  const menu = useQuery({ queryKey: adminConsoleKeys.menus, queryFn: adminConsole.menus })
  // 저장하면 편집기를 새로 그리므로 안내는 바깥에 둔다
  const [savedNotice, setSavedNotice] = useState(false)
  if (menu.isPending) return <p className="text-xs text-muted-foreground">불러오는 중…</p>
  if (menu.error) return <ErrorMessage error={menu.error} />
  // 저장된 메뉴가 바뀌면 편집 상태를 새로 시작
  return (
    <div className="space-y-3">
      {savedNotice && (
        <Alert tone="success">메뉴를 저장했습니다. 다른 직원은 화면을 새로 열면 바뀐 메뉴가 보입니다.</Alert>
      )}
      <MenuEditor
        key={JSON.stringify(menu.data)}
        saved={menu.data}
        onSaved={() => setSavedNotice(true)}
        onEdit={() => setSavedNotice(false)}
      />
    </div>
  )
}

type Draft = { label: string; items: { path: string; label: string }[] }[]

function clone(groups: MenuGroupDto[]): Draft {
  return groups.map((g) => ({ label: g.label, items: g.items.map((i) => ({ ...i })) }))
}

function move<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) return list
  const next = [...list]
  const [x] = next.splice(from, 1)
  next.splice(to, 0, x)
  return next
}

function MenuEditor({
  saved,
  onSaved,
  onEdit,
}: {
  saved: MenuGroupDto[]
  onSaved: () => void
  onEdit: () => void
}) {
  const client = useQueryClient()
  const [draft, setDraft] = useState<Draft>(() => clone(saved))
  const [newGroup, setNewGroup] = useState('')
  const [addPath, setAddPath] = useState('')
  const [addGroup, setAddGroup] = useState(0)
  const [reason, setReason] = useState('')
  const save = useMutation({
    mutationFn: () => adminConsole.saveMenus(draft, reason),
    onSuccess: (data) => {
      onSaved()
      client.setQueryData(adminConsoleKeys.menus, data)
    },
  })

  const dirty = JSON.stringify(draft) !== JSON.stringify(clone(saved))
  const used = new Set(draft.flatMap((g) => g.items.map((i) => i.path)))
  const unused = Object.keys(SCREENS).filter((p) => !used.has(p))
  const update = (fn: (d: Draft) => Draft) => {
    save.reset()
    onEdit()
    setDraft((d) => fn(d))
  }
  const setGroup = (gi: number, patch: Partial<Draft[number]>) =>
    update((d) => d.map((g, i) => (i === gi ? { ...g, ...patch } : g)))

  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle>메뉴 관리</CardTitle>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" disabled={!dirty} onClick={() => update(() => clone(saved))}>
              편집 취소
            </Button>
            <Button size="sm" variant="outline" onClick={() => update(() => clone(DEFAULT_MENU))}>
              처음 메뉴로
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-2 text-xs">
          <p className="text-muted-foreground">
            메뉴가 보이는지는 화면마다 필요한 권한으로 정해집니다(권한 관리). 메뉴에서 빼도 화면은 없어지지 않습니다. 메뉴
            관리·권한 관리 화면은 뺄 수 없습니다. (R70)
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1">
              <span className="block text-muted-foreground">새 대분류</span>
              <Input
                aria-label="새 대분류 이름"
                value={newGroup}
                maxLength={20}
                onChange={(e) => setNewGroup(e.target.value)}
                className="w-40"
              />
            </label>
            <Button
              size="sm"
              variant="outline"
              disabled={!newGroup.trim()}
              onClick={() => {
                update((d) => [...d, { label: newGroup.trim(), items: [] }])
                setNewGroup('')
              }}
            >
              대분류 추가
            </Button>
            <span className="mx-2 h-6 border-l" />
            <label className="space-y-1">
              <span className="block text-muted-foreground">메뉴에 없는 화면</span>
              <Select aria-label="추가할 화면" value={addPath} onChange={(e) => setAddPath(e.target.value)}>
                <option value="">{unused.length ? '화면 선택' : '모든 화면이 메뉴에 있음'}</option>
                {unused.map((p) => (
                  <option key={p} value={p}>
                    {SCREENS[p].label} ({p})
                  </option>
                ))}
              </Select>
            </label>
            <label className="space-y-1">
              <span className="block text-muted-foreground">넣을 대분류</span>
              <Select aria-label="넣을 대분류" value={addGroup} onChange={(e) => setAddGroup(Number(e.target.value))}>
                {draft.map((g, i) => (
                  <option key={i} value={i}>
                    {g.label}
                  </option>
                ))}
              </Select>
            </label>
            <Button
              size="sm"
              variant="outline"
              disabled={!addPath || !draft[addGroup]}
              onClick={() => {
                const item = { path: addPath, label: SCREENS[addPath].label }
                setGroup(addGroup, { items: [...draft[addGroup].items, item] })
                setAddPath('')
              }}
            >
              메뉴 추가
            </Button>
          </div>
        </CardContent>
      </Card>

      {draft.map((g, gi) => (
        <Card key={gi}>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Input
                aria-label={`대분류 이름 ${gi + 1}`}
                value={g.label}
                maxLength={20}
                onChange={(e) => setGroup(gi, { label: e.target.value })}
                className="w-40 font-semibold"
              />
              <span className="text-xs text-muted-foreground">메뉴 {g.items.length}개</span>
            </div>
            <div className="flex items-center gap-1">
              <Button size="sm" variant="outline" disabled={gi === 0} onClick={() => update((d) => move(d, gi, gi - 1))}>
                ▲
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={gi === draft.length - 1}
                onClick={() => update((d) => move(d, gi, gi + 1))}
              >
                ▼
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={g.items.length > 0}
                title={g.items.length > 0 ? '메뉴를 모두 옮기거나 뺀 뒤 삭제할 수 있습니다' : undefined}
                onClick={() => update((d) => d.filter((_, i) => i !== gi))}
              >
                대분류 삭제
              </Button>
            </div>
          </CardHeader>
          <CardContent>
            <Table>
              <THead>
                <TR>
                  <TH className="w-48">메뉴 이름</TH>
                  <TH>화면</TH>
                  <TH>필요 권한</TH>
                  <TH className="w-40">대분류 이동</TH>
                  <TH className="w-44" />
                </TR>
              </THead>
              <TBody>
                {g.items.length === 0 && (
                  <TR>
                    <TD colSpan={5} className="text-center text-muted-foreground">
                      메뉴가 없습니다
                    </TD>
                  </TR>
                )}
                {g.items.map((item, ii) => {
                  const screen = SCREENS[item.path]
                  const required = REQUIRED_PATHS.includes(item.path)
                  const setItems = (items: Draft[number]['items']) => setGroup(gi, { items })
                  return (
                    <TR key={item.path}>
                      <TD>
                        <Input
                          aria-label={`메뉴 이름 ${item.path}`}
                          value={item.label}
                          maxLength={20}
                          onChange={(e) =>
                            setItems(g.items.map((x, i) => (i === ii ? { ...x, label: e.target.value } : x)))
                          }
                        />
                      </TD>
                      <TD className="text-muted-foreground">
                        {screen?.label ?? '없는 화면'} <span className="font-mono">{item.path}</span>
                      </TD>
                      <TD className="font-mono text-muted-foreground">
                        {screen?.permission ?? screen?.anyOf?.join(' / ') ?? '로그인한 모든 직원'}
                      </TD>
                      <TD>
                        <Select
                          aria-label={`대분류 이동 ${item.path}`}
                          value={gi}
                          onChange={(e) => {
                            const to = Number(e.target.value)
                            update((d) =>
                              d.map((x, i) =>
                                i === gi
                                  ? { ...x, items: x.items.filter((_, k) => k !== ii) }
                                  : i === to
                                    ? { ...x, items: [...x.items, item] }
                                    : x,
                              ),
                            )
                          }}
                        >
                          {draft.map((x, i) => (
                            <option key={i} value={i}>
                              {x.label}
                            </option>
                          ))}
                        </Select>
                      </TD>
                      <TD className="space-x-1 text-right whitespace-nowrap">
                        <Button size="sm" variant="outline" disabled={ii === 0} onClick={() => setItems(move(g.items, ii, ii - 1))}>
                          ▲
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={ii === g.items.length - 1}
                          onClick={() => setItems(move(g.items, ii, ii + 1))}
                        >
                          ▼
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={required}
                          title={required ? '메뉴에서 뺄 수 없는 화면입니다' : undefined}
                          onClick={() => setItems(g.items.filter((_, i) => i !== ii))}
                        >
                          빼기
                        </Button>
                      </TD>
                    </TR>
                  )
                })}
              </TBody>
            </Table>
          </CardContent>
        </Card>
      ))}

      <Card>
        <CardContent className="space-y-2 pt-3 text-xs">
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex-1 space-y-1">
              <span className="block text-muted-foreground">변경 사유 (2~200자, 접근 기록에 남음)</span>
              <Input aria-label="변경 사유" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
            </label>
            <Button disabled={!dirty || reason.trim().length < 2 || save.isPending} onClick={() => save.mutate()}>
              메뉴 저장
            </Button>
          </div>
          <ErrorMessage error={save.error} />
          {dirty && !save.isPending && <p className="text-amber-600">저장하지 않은 변경이 있습니다.</p>}
        </CardContent>
      </Card>
    </div>
  )
}
