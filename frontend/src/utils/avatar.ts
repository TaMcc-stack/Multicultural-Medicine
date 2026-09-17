/** 头像配色预设：与后端 AuthService.AVATAR_KEYS 一一对应，两端都改才生效。 */
export interface AvatarPreset {
  key: string
  label: string
  from: string
  to: string
}

export const AVATAR_PRESETS: AvatarPreset[] = [
  { key: 'sage', label: '鼠尾草', from: '#4E7D68', to: '#305344' },
  { key: 'clay', label: '陶土', from: '#C96C43', to: '#9A4628' },
  { key: 'amber', label: '琥珀', from: '#D9A44C', to: '#A9701F' },
  { key: 'plum', label: '紫李', from: '#8E5A78', to: '#6A3F58' },
  { key: 'sky', label: '远山', from: '#5B7F9E', to: '#3D5C77' },
  { key: 'moss', label: '苔绿', from: '#6E8B4A', to: '#4E6633' },
  { key: 'rust', label: '赭石', from: '#A85B4B', to: '#7E3F33' },
  { key: 'ink', label: '墨青', from: '#4A5560', to: '#2F3944' },
]

/** 按 key 取预设；key 为空或没匹配上时回退到第一个（sage）。 */
export function avatarPresetOf(key?: string | null): AvatarPreset {
  return AVATAR_PRESETS.find((x) => x.key === key) || AVATAR_PRESETS[0]!
}

/** 头像上的字：昵称首字（去掉首尾空白，空则回退成「用」）。 */
export function avatarInitial(name: string): string {
  const n = name.trim()
  return n ? Array.from(n)[0]! : '用'
}

/** 「首字 + 渐变底」头像的背景样式。个人中心、动态卡片、动态详情弹窗共用。 */
export function avatarStyleOf(key?: string | null) {
  const p = avatarPresetOf(key)
  return { background: `linear-gradient(150deg, ${p.from}, ${p.to})` }
}
