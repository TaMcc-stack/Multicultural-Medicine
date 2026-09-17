import request from './request'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  /** 头像配色 key（见 ProfileView 的 AVATAR_PRESETS），不是图片地址 */
  avatar: string
  /** 个人简介 */
  bio: string
}

/** 可更新的个人资料字段 */
export interface UpdateProfilePayload {
  nickname: string
  avatar: string
  bio: string
}

export interface LoginResponse {
  token: string
  user: UserInfo
}

/** 登录 */
export function login(data: { username: string; password: string }): Promise<LoginResponse> {
  return request.post('/auth/login', data)
}

/** 注册（成功后自动登录） */
export function register(data: { username: string; password: string; nickname?: string }): Promise<LoginResponse> {
  return request.post('/auth/register', data)
}

/** 退出登录 */
export function logout(): Promise<void> {
  return request.post('/auth/logout')
}

/** 当前登录用户信息 */
export function me(): Promise<UserInfo> {
  return request.get('/auth/me')
}

/** 更新个人资料（昵称 / 头像配色 / 简介），返回更新后的用户信息 */
export function updateProfile(data: UpdateProfilePayload): Promise<UserInfo> {
  return request.put('/auth/me', data)
}

/**
 * 上传自定义头像。成功后 user.avatar 变成 `upload:{版本号}`。
 * 后端会嗅探文件头判断真实类型，只接受 PNG / JPG / WebP，且不超过 2MB。
 */
export function uploadAvatar(file: File): Promise<UserInfo> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post<UserInfo>('/auth/avatar', fd)
}

/**
 * 自定义头像的读取地址；未使用自定义头像时返回空串。
 *
 * 参数只声明真正用到的两个字段（id + avatar），而不是整个 `UserInfo`：
 * 留言板的反馈 DTO 带的是扁平的 `userId` / `avatar`，放宽之后可以直接传过去，
 * 不必为凑类型去编一个用不上的 `UserInfo`。
 */
export function avatarUrlOf(user: { id: number; avatar?: string | null } | null): string {
  const a = user?.avatar || ''
  // 第二个条件同时兜住了 user 为空的情况：`a` 是从 user 取的，走到下面必然有 user。
  // 但类型系统看不出这层关联，所以把 !user 显式写出来收窄。
  if (!user || !a.startsWith('upload:')) return ''
  // 版本号用于破浏览器缓存：同 URL 重新上传后能立刻看到新图
  return `/api/auth/avatar/${user.id}?v=${a.slice('upload:'.length)}`
}
