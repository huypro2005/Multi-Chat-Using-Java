import { z } from 'zod'

export const registerSchema = z
  .object({
    email: z
      .string()
      .min(1, 'Email không được để trống')
      .email('Email không đúng định dạng'),
    username: z
      .string()
      .min(3, 'Username phải có ít nhất 3 ký tự')
      .max(50, 'Username tối đa 50 ký tự')
      .regex(
        /^[a-zA-Z0-9_]+$/,
        'Username chỉ chứa chữ cái, số và dấu gạch dưới'
      ),
    fullName: z
      .string()
      .min(1, 'Họ tên không được để trống')
      .max(100, 'Họ tên tối đa 100 ký tự'),
    password: z
      .string()
      .min(8, 'Mật khẩu phải có ít nhất 8 ký tự')
      .regex(/[A-Z]/, 'Mật khẩu phải có ít nhất 1 chữ hoa')
      .regex(/[0-9]/, 'Mật khẩu phải có ít nhất 1 chữ số'),
    confirmPassword: z.string().min(1, 'Vui lòng xác nhận mật khẩu'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Mật khẩu xác nhận không khớp',
    path: ['confirmPassword'],
  })

export type RegisterFormData = z.infer<typeof registerSchema>
