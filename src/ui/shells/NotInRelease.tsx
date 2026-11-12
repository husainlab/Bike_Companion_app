import React from 'react';
import { Redirect } from 'expo-router';

export function NotInRelease({ children }: { children: React.ReactNode }) {
  if (!__DEV__) {
    return <Redirect href="/" />;
  }
  return <>{children}</>;
}

