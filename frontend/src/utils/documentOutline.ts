import type { ContentBlock } from './contentBlocks'

/** 目录树上的一个节点（一个知识单元切片） */
export interface OutlineNode {
  /** 稳定唯一 id，如 'sec-1'、'sec-1-2'、'sec-1-2-a1' */
  id: string
  /** 目录显示名：标题原文，或自动概括的短标题 */
  title: string
  /** 结构层级：1/2/3；自动生成项 = 父级 level + 1 */
  level: number
  /** 上级标题上下文（从顶级到直接父级，不含自身） */
  context: string[]
  /** 本节点自带内容块（含非结构性标题、段落、表格、列表），不含子节点内容 */
  blocks: ContentBlock[]
  children: OutlineNode[]
}

export interface DocumentOutline {
  /** 顶级节点，文档顺序 */
  nodes: OutlineNode[]
  /** 深度优先展开，文档顺序；供默认选中/上一节下一节使用 */
  flat: OutlineNode[]
}

const STRUCTURAL_HEADING_RE =
  /^\s*(?:[一二三四五六七八九十百千]+[、.．]|\d+\s*[、.．]|\(\d+\)|（\d+）)/
const AUTO_OUTLINE_ITEM_RE = /^\s*(?:（\d+）|\(\d+\)|\d+\s*[、.])/
const AUTO_TITLE_STOP_CHARS = new Set(['。', '；', '，', '、', '：', '！', '？'])
const AUTO_TITLE_MAX_LENGTH = 18
const AUTO_OUTLINE_ITEM_LIMIT = 12

function isStructuralHeading(block: ContentBlock): boolean {
  if (block.type !== 'h') return false
  if (block.level === 1) return true
  if (block.level !== 2 && block.level !== 3) return false
  return STRUCTURAL_HEADING_RE.test(block.text ?? '')
}

function hasSubstantiveContent(blocks: ContentBlock[]): boolean {
  return blocks.some((block) => {
    if (block.type === 'h' || block.type === 'p') {
      return !!block.text?.trim()
    }
    if (block.type === 'list') {
      return !!block.items?.some((item) => item.trim())
    }
    if (block.type === 'table') {
      return !!block.rows?.some((row) => row.some((cell) => cell.trim()))
    }
    return false
  })
}

function cloneBlock(block: ContentBlock): ContentBlock {
  const cloned: ContentBlock = { ...block }
  if (block.rows) cloned.rows = block.rows.map((row) => [...row])
  if (block.items) cloned.items = [...block.items]
  return cloned
}

function assignIds(nodes: OutlineNode[], parentId = ''): void {
  nodes.forEach((node, index) => {
    node.id = parentId ? `${parentId}-${index + 1}` : `sec-${index + 1}`
    assignIds(node.children, node.id)
  })
}

function summarizeAutoTitle(text: string, prefixLength: number): string {
  const body = text.slice(prefixLength).trim()
  if (!body) return text.trim()

  let title = ''
  for (const char of body) {
    if (AUTO_TITLE_STOP_CHARS.has(char)) break
    title += char
    if (title.length > AUTO_TITLE_MAX_LENGTH) {
      return `${title.slice(0, AUTO_TITLE_MAX_LENGTH)}…`
    }
  }

  const trimmed = title.trim()
  if (trimmed) return trimmed
  return body.length > AUTO_TITLE_MAX_LENGTH
    ? `${body.slice(0, AUTO_TITLE_MAX_LENGTH)}…`
    : body
}

function addAutoChildren(node: OutlineNode): void {
  if (node.children.length > 0) {
    node.children.forEach(addAutoChildren)
    return
  }

  const autoNodes: OutlineNode[] = []
  const retainedBlocks: ContentBlock[] = []

  node.blocks.forEach((block) => {
    if (autoNodes.length >= AUTO_OUTLINE_ITEM_LIMIT || block.type !== 'p') {
      retainedBlocks.push(block)
      return
    }

    const text = block.text ?? ''
    const match = text.trim().match(AUTO_OUTLINE_ITEM_RE)
    if (!match || text.trim().length < 12) {
      retainedBlocks.push(block)
      return
    }

    // 父节点保留原文：点父章节时能看到整节内容，子节点只承载单条知识单元
    retainedBlocks.push(block)
    autoNodes.push({
      id: `${node.id}-a${autoNodes.length + 1}`,
      title: summarizeAutoTitle(text, match[0].length),
      level: node.level + 1,
      context: [...node.context, node.title],
      blocks: [{ type: 'p', text }],
      children: [],
    })
  })

  node.blocks = retainedBlocks
  node.children = autoNodes
}

function flattenOutline(nodes: OutlineNode[]): OutlineNode[] {
  const flat: OutlineNode[] = []
  const visit = (items: OutlineNode[]) => {
    items.forEach((node) => {
      flat.push(node)
      visit(node.children)
    })
  }
  visit(nodes)
  return flat
}

export function buildDocumentOutline(blocks: ContentBlock[]): DocumentOutline {
  const nodes: OutlineNode[] = []
  const stack: OutlineNode[] = []

  let current: OutlineNode | null = null
  for (const block of blocks) {
    if (isStructuralHeading(block)) {
      while (stack.length > 0 && stack[stack.length - 1]!.level >= block.level!) {
        stack.pop()
      }

      const parent = stack[stack.length - 1] ?? null
      const node: OutlineNode = {
        id: '',
        title: (block.text ?? '').trim(),
        level: block.level!,
        context: parent ? [...parent.context, parent.title] : [],
        blocks: [],
        children: [],
      }

      if (parent) parent.children.push(node)
      else nodes.push(node)

      stack.push(node)
      current = node
      continue
    }

    if (current) current.blocks.push(cloneBlock(block))
  }

  assignIds(nodes)
  nodes.forEach(addAutoChildren)

  const firstStructuralIndex = blocks.findIndex(isStructuralHeading)
  const preambleBlocks =
    firstStructuralIndex === -1 ? blocks : blocks.slice(0, firstStructuralIndex)
  if (hasSubstantiveContent(preambleBlocks)) {
    nodes.unshift({
      id: 'sec-preamble',
      title: '开头',
      level: 0,
      context: [],
      blocks: preambleBlocks.map(cloneBlock),
      children: [],
    })
    addAutoChildren(nodes[0]!)
  }

  return {
    nodes,
    flat: flattenOutline(nodes),
  }
}
