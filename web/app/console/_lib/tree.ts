/**
 * 자기참조 parentId 로 표현된 부서 목록을 화면용 트리 순서로 편다.
 *
 * 서버는 flat 목록만 내려주고 트리 구성은 콘솔 책임이다(서버 DTO 주석). 여기서 두 가지를 지킨다:
 *  - **고아(부모가 목록에 없는 노드)를 버리지 않는다.** 루트로 승격시켜 항상 보이게 한다 — 데이터가
 *    이상하면 화면에서 티가 나야 한다(조용히 사라지면 인원이 맞지 않는 이유를 아무도 못 찾는다).
 *  - **순환 참조에도 멈춘다.** 방문한 노드를 다시 펼치지 않아 무한 재귀로 탭이 죽지 않는다.
 */
export interface TreeItem {
  id: string;
  parentId: string | null;
  name: string;
}

export interface FlatNode<T> {
  item: T;
  depth: number;
  hasChildren: boolean;
}

export function flattenTree<T extends TreeItem>(items: T[]): FlatNode<T>[] {
  const byId = new Map(items.map((item) => [item.id, item]));
  const children = new Map<string | null, T[]>();

  for (const item of items) {
    // 부모가 목록에 없으면 루트로 취급(고아 보존).
    const parent = item.parentId && byId.has(item.parentId) ? item.parentId : null;
    const bucket = children.get(parent);
    if (bucket) bucket.push(item);
    else children.set(parent, [item]);
  }

  for (const bucket of children.values()) {
    bucket.sort((a, b) => a.name.localeCompare(b.name, "ko"));
  }

  const out: FlatNode<T>[] = [];
  const visited = new Set<string>();

  const walk = (parentId: string | null, depth: number) => {
    for (const item of children.get(parentId) ?? []) {
      if (visited.has(item.id)) continue; // 순환 방어
      visited.add(item.id);
      const kids = children.get(item.id) ?? [];
      out.push({ item, depth, hasChildren: kids.length > 0 });
      walk(item.id, depth + 1);
    }
  };

  walk(null, 0);

  // 순환 때문에 어느 갈래에도 못 붙은 노드가 남으면 마지막에 그대로 덧붙인다(누락 금지).
  for (const item of items) {
    if (!visited.has(item.id)) out.push({ item, depth: 0, hasChildren: false });
  }
  return out;
}
