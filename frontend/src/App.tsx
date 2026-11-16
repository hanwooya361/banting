import { Route, Routes } from 'react-router'
import { Layout } from './components/Layout'
import { RequireAuth } from './components/RequireAuth'
import { HomePage } from './pages/HomePage'
import { LocationPage } from './pages/LocationPage'
import { LoginPage } from './pages/LoginPage'
import { MePage } from './pages/MePage'
import { SignupPage } from './pages/SignupPage'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="signup" element={<SignupPage />} />
        <Route
          path="me"
          element={
            <RequireAuth>
              <MePage />
            </RequireAuth>
          }
        />
        <Route
          path="me/location"
          element={
            <RequireAuth>
              <LocationPage />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  )
}

export default App
