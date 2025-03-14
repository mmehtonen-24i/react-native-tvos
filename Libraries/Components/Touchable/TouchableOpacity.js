/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * @flow strict-local
 * @format
 */

import Pressability, {
  type PressabilityConfig,
} from '../../Pressability/Pressability';
import {PressabilityDebugView} from '../../Pressability/PressabilityDebug';
import TVTouchable from './TVTouchable';
import typeof TouchableWithoutFeedback from './TouchableWithoutFeedback';
import Animated from '../../Animated/Animated';
import Easing from '../../Animated/Easing';
import type {ViewStyleProp} from '../../StyleSheet/StyleSheet';
import flattenStyle from '../../StyleSheet/flattenStyle';
import Platform from '../../Utilities/Platform';
import type {TVParallaxPropertiesType} from '../TV/TVViewPropTypes';
import tagForComponentOrHandle from '../TV/tagForComponentOrHandle';

import * as React from 'react';

type TVProps = $ReadOnly<{|
  hasTVPreferredFocus?: ?boolean,
  isTVSelectable?: ?boolean,
  tvParallaxProperties?: TVParallaxPropertiesType,
  nextFocusDown?: ?number,
  nextFocusForward?: ?number,
  nextFocusLeft?: ?number,
  nextFocusRight?: ?number,
  nextFocusUp?: ?number,
|}>;

type Props = $ReadOnly<{|
  ...React.ElementConfig<TouchableWithoutFeedback>,
  ...TVProps,

  activeOpacity?: ?number,
  style?: ?ViewStyleProp,

  hostRef?: ?React.Ref<typeof Animated.View>,
|}>;

type State = $ReadOnly<{|
  anim: Animated.Value,
  pressability: Pressability,
|}>;

/**
 * A wrapper for making views respond properly to touches.
 * On press down, the opacity of the wrapped view is decreased, dimming it.
 *
 * Opacity is controlled by wrapping the children in an Animated.View, which is
 * added to the view hierarchy.  Be aware that this can affect layout.
 *
 * Example:
 *
 * ```
 * renderButton: function() {
 *   return (
 *     <TouchableOpacity onPress={this._onPressButton}>
 *       <Image
 *         style={styles.button}
 *         source={require('./myButton.png')}
 *       />
 *     </TouchableOpacity>
 *   );
 * },
 * ```
 * ### Example
 *
 * ```ReactNativeWebPlayer
 * import React, { Component } from 'react'
 * import {
 *   AppRegistry,
 *   StyleSheet,
 *   TouchableOpacity,
 *   Text,
 *   View,
 * } from 'react-native'
 *
 * class App extends Component {
 *   state = { count: 0 }
 *
 *   onPress = () => {
 *     this.setState(state => ({
 *       count: state.count + 1
 *     }));
 *   };
 *
 *  render() {
 *    return (
 *      <View style={styles.container}>
 *        <TouchableOpacity
 *          style={styles.button}
 *          onPress={this.onPress}>
 *          <Text> Touch Here </Text>
 *        </TouchableOpacity>
 *        <View style={[styles.countContainer]}>
 *          <Text style={[styles.countText]}>
 *             { this.state.count !== 0 ? this.state.count: null}
 *           </Text>
 *         </View>
 *       </View>
 *     )
 *   }
 * }
 *
 * const styles = StyleSheet.create({
 *   container: {
 *     flex: 1,
 *     justifyContent: 'center',
 *     paddingHorizontal: 10
 *   },
 *   button: {
 *     alignItems: 'center',
 *     backgroundColor: '#DDDDDD',
 *     padding: 10
 *   },
 *   countContainer: {
 *     alignItems: 'center',
 *     padding: 10
 *   },
 *   countText: {
 *     color: '#FF00FF'
 *   }
 * })
 *
 * AppRegistry.registerComponent('App', () => App)
 * ```
 *
 */
class TouchableOpacity extends React.Component<Props, State> {
  _tvTouchable: ?TVTouchable;

  state: State = {
    anim: new Animated.Value(this._getChildStyleOpacityWithDefault()),
    pressability: new Pressability(this._createPressabilityConfig()),
  };

  _createPressabilityConfig(): PressabilityConfig {
    return {
      cancelable: !this.props.rejectResponderTermination,
      disabled: this.props.disabled ?? this.props.accessibilityState?.disabled,
      hitSlop: this.props.hitSlop,
      delayLongPress: this.props.delayLongPress,
      delayPressIn: this.props.delayPressIn,
      delayPressOut: this.props.delayPressOut,
      minPressDuration: 0,
      pressRectOffset: this.props.pressRetentionOffset,
      onBlur: event => {
        if (Platform.isTV) {
          this._opacityInactive(250);
        }
        if (this.props.onBlur != null) {
          this.props.onBlur(event);
        }
      },
      onFocus: event => {
        if (Platform.isTV) {
          this._opacityActive(150);
        }
        if (this.props.onFocus != null) {
          this.props.onFocus(event);
        }
      },
      onLongPress: this.props.onLongPress,
      onPress: this.props.onPress,
      onPressIn: event => {
        this._opacityActive(
          event.dispatchConfig.registrationName === 'onResponderGrant'
            ? 0
            : 150,
        );
        if (this.props.onPressIn != null) {
          this.props.onPressIn(event);
        }
      },
      onPressOut: event => {
        this._opacityInactive(250);
        if (this.props.onPressOut != null) {
          this.props.onPressOut(event);
        }
      },
    };
  }

  /**
   * Animate the touchable to a new opacity.
   */
  _setOpacityTo(toValue: number, duration: number): void {
    Animated.timing(this.state.anim, {
      toValue,
      duration,
      easing: Easing.inOut(Easing.quad),
      useNativeDriver: true,
    }).start();
  }

  _opacityActive(duration: number): void {
    this._setOpacityTo(this.props.activeOpacity ?? 0.2, duration);
  }

  _opacityInactive(duration: number): void {
    this._setOpacityTo(this._getChildStyleOpacityWithDefault(), duration);
  }

  _getChildStyleOpacityWithDefault(): number {
    const opacity = flattenStyle(this.props.style)?.opacity;
    return typeof opacity === 'number' ? opacity : 1;
  }

  render(): React.Node {
    // BACKWARD-COMPATIBILITY: Focus and blur events were never supported before
    // adopting `Pressability`, so preserve that behavior.
    const {onBlur, onFocus, ...eventHandlersWithoutBlurAndFocus} =
      this.state.pressability.getEventHandlers();

    const accessibilityState =
      this.props.disabled != null
        ? {
            ...this.props.accessibilityState,
            disabled: this.props.disabled,
          }
        : this.props.accessibilityState;

    return (
      <Animated.View
        accessible={this.props.accessible !== false}
        accessibilityLabel={this.props.accessibilityLabel}
        accessibilityHint={this.props.accessibilityHint}
        accessibilityLanguage={this.props.accessibilityLanguage}
        accessibilityRole={this.props.accessibilityRole}
        accessibilityState={accessibilityState}
        accessibilityActions={this.props.accessibilityActions}
        onAccessibilityAction={this.props.onAccessibilityAction}
        accessibilityValue={this.props.accessibilityValue}
        importantForAccessibility={this.props.importantForAccessibility}
        accessibilityLiveRegion={this.props.accessibilityLiveRegion}
        accessibilityViewIsModal={this.props.accessibilityViewIsModal}
        accessibilityElementsHidden={this.props.accessibilityElementsHidden}
        style={[this.props.style, {opacity: this.state.anim}]}
        nativeID={this.props.nativeID}
        testID={this.props.testID}
        onLayout={this.props.onLayout}
        nextFocusDown={tagForComponentOrHandle(this.props.nextFocusDown)}
        nextFocusForward={tagForComponentOrHandle(this.props.nextFocusForward)}
        nextFocusLeft={tagForComponentOrHandle(this.props.nextFocusLeft)}
        nextFocusRight={tagForComponentOrHandle(this.props.nextFocusRight)}
        nextFocusUp={tagForComponentOrHandle(this.props.nextFocusUp)}
        hasTVPreferredFocus={this.props.hasTVPreferredFocus === true}
        isTVSelectable={
          this.props.isTVSelectable !== false && this.props.accessible !== false
        }
        tvParallaxProperties={this.props.tvParallaxProperties}
        hitSlop={this.props.hitSlop}
        focusable={
          this.props.focusable !== false && this.props.onPress !== undefined
        }
        ref={this.props.hostRef}
        {...eventHandlersWithoutBlurAndFocus}>
        {this.props.children}
        {__DEV__ ? (
          <PressabilityDebugView color="cyan" hitSlop={this.props.hitSlop} />
        ) : null}
      </Animated.View>
    );
  }

  componentDidMount(): void {
    if (Platform.isTV) {
      this._tvTouchable = new TVTouchable(this, {
        getDisabled: () => this.props.disabled === true,
        onBlur: event => {
          this._opacityInactive(250);
          if (this.props.onBlur != null) {
            this.props.onBlur(event);
          }
        },
        onFocus: event => {
          this._opacityActive(150);
          if (this.props.onFocus != null) {
            this.props.onFocus(event);
          }
        },
        onPress: event => {
          if (this.props.onPress != null && Platform.OS !== 'android') {
            this.props.onPress(event);
          }
        },
        onLongPress: event => {
          if (this.props.onLongPress != null && Platform.OS !== 'android') {
            this.props.onLongPress(event);
          }
        },
      });
    }
  }

  componentDidUpdate(prevProps: Props, prevState: State) {
    this.state.pressability.configure(this._createPressabilityConfig());
    if (
      this.props.disabled !== prevProps.disabled ||
      (flattenStyle(prevProps.style)?.opacity !==
        flattenStyle(this.props.style)?.opacity) !==
        undefined
    ) {
      this._opacityInactive(250);
    }
  }

  componentWillUnmount(): void {
    if (Platform.isTV) {
      if (this._tvTouchable != null) {
        this._tvTouchable.destroy();
      }
    }
    this.state.pressability.reset();
  }
}

const Touchable = (React.forwardRef((props, ref) => (
  <TouchableOpacity {...props} hostRef={ref} />
)): React.AbstractComponent<Props, React.ElementRef<typeof Animated.View>>);

Touchable.displayName = 'TouchableOpacity';

module.exports = Touchable;
