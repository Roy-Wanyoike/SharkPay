/**
 * Navigation types — react-navigation was picked over expo-router (one
 * navigation solution, implemented fully; the reasoning is in the app
 * README). Route params are explicit so every screen is callable from tests
 * without a navigator.
 */

import type { NavigatorScreenParams } from '@react-navigation/native';

export type AppTabParamList = {
  Home: undefined;
  Activity: undefined;
  Payouts: undefined;
  Settings: undefined;
};

export type RootStackParamList = {
  Tabs: NavigatorScreenParams<AppTabParamList>;
  Send: undefined;
};

/** Route declaration merging so useNavigation() is fully typed everywhere. */
declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace ReactNavigation {
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type
    interface RootParamList extends RootStackParamList, AppTabParamList {}
  }
}
