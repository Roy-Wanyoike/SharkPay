/**
 * App root — providers (safe area → app store) + the navigation container
 * and root navigator (react-navigation; see src/navigation).
 *
 * The OAuth redirect never routes through navigation linking: the browser
 * round-trip is owned by expo-auth-session's auth session (WebBrowser), and
 * the store's session phase drives which tree the navigator renders.
 */

import React from 'react';
import { NavigationContainer, DarkTheme, DefaultTheme } from '@react-navigation/native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { useColorScheme } from 'react-native';

import { palette } from './components/theme';
import { RootNavigator } from './navigation/RootNavigator';
import { AppProvider } from './state/AppStore';

function Root() {
  const colorScheme = useColorScheme();
  const navigationTheme =
    colorScheme === 'dark'
      ? { ...DarkTheme, colors: { ...DarkTheme.colors, primary: palette.brand } }
      : { ...DefaultTheme, colors: { ...DefaultTheme.colors, primary: palette.brand, background: palette.background } };
  return (
    <>
      <StatusBar style="dark" />
      <NavigationContainer theme={navigationTheme}>
        <RootNavigator />
      </NavigationContainer>
    </>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppProvider>
        <Root />
      </AppProvider>
    </SafeAreaProvider>
  );
}
