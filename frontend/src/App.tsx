import { Navigate, Route, Routes } from 'react-router'
import { AlertsPage } from '@/features/collection/AlertsPage'
import { Layout } from '@/components/Layout'
import { AdminPage } from '@/features/admin/AdminPage'
import { useAuth } from '@/features/auth/auth'
import { LoginPage } from '@/features/auth/LoginPage'
import { PasswordChangePage } from '@/features/auth/PasswordChangePage'
import { BulkPage } from '@/features/bulk/BulkPage'
import { LoanDetailPage } from '@/features/loans/LoanDetailPage'
import { LoanListPage } from '@/features/loans/LoanListPage'
import { NewLoanPage } from '@/features/loans/NewLoanPage'
import { CorrectionsPage } from '@/features/payments/CorrectionsPage'
import { UnmatchedPage } from '@/features/payments/UnmatchedPage'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export function App() {
  const { me, loading } = useAuth()
  if (loading) return <div className="p-6 text-xs text-muted-foreground">불러오는 중…</div>
  if (!me) return <LoginPage />
  if (me.passwordChangeRequired) return <PasswordChangePage forced />

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/loans" replace />} />
        <Route path="loans" element={<LoanListPage />} />
        <Route path="loans/new" element={<NewLoanPage />} />
        <Route path="loans/:id" element={<LoanDetailPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="unmatched" element={<UnmatchedPage />} />
        <Route path="corrections" element={<CorrectionsPage />} />
        <Route path="bulk" element={<BulkPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route
          path="account"
          element={
            <Card className="max-w-md">
              <CardHeader>
                <CardTitle>비밀번호 변경</CardTitle>
              </CardHeader>
              <CardContent>
                <PasswordChangePage />
              </CardContent>
            </Card>
          }
        />
        <Route path="*" element={<p className="text-xs">페이지가 없습니다.</p>} />
      </Route>
    </Routes>
  )
}
