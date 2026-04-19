import { z } from 'zod'

export const loginSchema = z.object({
  username: z
    .string()
    .min(1, 'Tên đăng nhập không được để trống')
    .max(50, 'Tên đăng nhập tối đa 50 ký tự'),
  password: z
    .string()
    .min(1, 'Mật khẩu không được để trống')
    .max(128, 'Mật khẩu tối đa 128 ký tự'),
})

export type LoginFormData = z.infer<typeof loginSchema>
