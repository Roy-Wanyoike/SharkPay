/**
 * RootNavigator — the auth-gated navigation tree (react-navigation v7).
 *
 * The navigator renders exactly one tree per session phase (the documented
 * conditional-auth pattern): restoring ⇒ Splash, anonymous ⇒ Login,
 * authenticated ⇒ the wallet stack (bottom tabs + the Send flow). Switching
 * trees is driven by the store, not by imperative navigation, so an ended
 * session can never leave wallet screens reachable.
 */

import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Text } from 'react-native';

import { palette, typography } from '../components/theme';
import { useApp } from '../state/AppStore';
import { ActivityScreen } from '../screens/ActivityScreen';
import { HomeScreen } from '../screens/HomeScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { PayoutsScreen } from '../screens/PayoutsScreen';
import { SendScreen } from '../screens/SendScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { SplashScreen } from '../screens/SplashScreen';
import type { AppTabParamList, RootStackParamList } from './types';

const Tab = createBottomTabNavigator<AppTabParamList>();
const Stack = createNativeStackNavigator<RootStackParamList>();

/** Text glyphs for the tab bar (asset-free; an icon set lands with design). */
const TAB_GLYPHS: Record<keyof AppTabParamList, string> = {
  Home: 'W',
  Activity: 'A',
  Payouts: 'P',
  Settings: 'S',
};

function WalletTabs() {
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: palette.brand,
        tabBarInactiveTintColor: palette.textMuted,
        tabBarIcon: ({ color }) => (
          <Text style={{ color, fontWeight: '800', fontSize: typography.label }}>
            {TAB_GLYPHS[route.name]}
          </Text>
        ),
      })}
    >
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Activity" component={ActivityScreen} />
      <Tab.Screen name="Payouts" component={PayoutsScreen} />
      <Tab.Screen name="Settings" component={SettingsScreen} />
    </Tab.Navigator>
  );
}

function WalletStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      <Stack.Screen name="Tabs" component={WalletTabs} />
      <Stack.Screen
        name="Send"
        component={SendScreen}
        options={{ presentation: 'modal', gestureEnabled: false }}
      />
    </Stack.Navigator>
  );
}

export function RootNavigator() {
  const { state } = useApp();
  if (state.sessionPhase === 'restoring') {
    return <SplashScreen />;
  }
  if (state.sessionPhase === 'anonymous') {
    return <LoginScreen />;
  }
  return <WalletStack />;
}
