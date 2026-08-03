import { createBrowserRouter } from 'react-router'
import { CatchAll, ChatRoute, LoginRoute, RootLayout } from './App'

export const router = createBrowserRouter([
  {
    path: '/',
    Component: RootLayout,
    children: [
      { index: true, Component: ChatRoute },
      { path: 'login', Component: LoginRoute },
      { path: '*', Component: CatchAll },
    ],
  },
])
