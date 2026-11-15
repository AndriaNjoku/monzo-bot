import React, { ReactNode } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TextInputProps,
  View,
} from 'react-native';

export const palette = {
  ink: '#183343',
  muted: '#667780',
  paper: '#F6F5F0',
  white: '#FFFFFF',
  coral: '#FF7B6D',
  paleCoral: '#FFF0EB',
  mint: '#CBE9DB',
  line: '#E3E7E4',
  red: '#A83935',
};
export function Heading({
  children,
  small = false,
}: {
  children: ReactNode;
  small?: boolean;
}) {
  return (
    <Text style={small ? styles.headingSmall : styles.heading}>{children}</Text>
  );
}
export function Body({
  children,
  muted = true,
}: {
  children: ReactNode;
  muted?: boolean;
}) {
  return (
    <Text style={[styles.body, !muted && { color: palette.ink }]}>
      {children}
    </Text>
  );
}
export function Card({
  title,
  children,
  tint = false,
}: {
  title?: string;
  children: ReactNode;
  tint?: boolean;
}) {
  return (
    <View style={[styles.card, tint && { backgroundColor: palette.paleCoral }]}>
      {title ? <Heading small>{title}</Heading> : null}
      {children}
    </View>
  );
}
export function Button({
  children,
  onPress,
  disabled,
  secondary = false,
  danger = false,
  testID,
}: {
  children: ReactNode;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
  danger?: boolean;
  testID?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      testID={testID}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        secondary && styles.secondary,
        danger && { backgroundColor: '#FCE6E0' },
        disabled && { opacity: 0.45 },
        pressed && { opacity: 0.7 },
      ]}
    >
      <Text style={[styles.buttonText, danger && { color: palette.red }]}>
        {children}
      </Text>
    </Pressable>
  );
}
export function Field({ label, ...props }: TextInputProps & { label: string }) {
  return (
    <View style={styles.fieldWrap}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor="#84908F"
        style={[
          styles.field,
          props.multiline && { minHeight: 112, textAlignVertical: 'top' },
        ]}
        autoCorrect={!props.secureTextEntry}
        autoCapitalize="sentences"
        {...props}
      />
    </View>
  );
}
export function Toggle({
  label,
  value,
  onChange,
  hint,
  disabled,
}: {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
  hint?: string;
  disabled?: boolean;
}) {
  return (
    <View style={styles.toggle}>
      <View style={{ flex: 1 }}>
        <Text style={styles.label}>{label}</Text>
        {hint ? <Body>{hint}</Body> : null}
      </View>
      <Switch
        accessibilityLabel={label}
        value={value}
        disabled={disabled}
        onValueChange={onChange}
        trackColor={{ false: '#D6DEDA', true: palette.coral }}
        thumbColor={palette.white}
      />
    </View>
  );
}
export function Note({
  children,
  error = false,
}: {
  children: ReactNode;
  error?: boolean;
}) {
  return (
    <View
      accessibilityLiveRegion="polite"
      style={[styles.note, error && { backgroundColor: '#FCE6E0' }]}
    >
      <Text style={[styles.noteText, error && { color: palette.red }]}>
        {children}
      </Text>
    </View>
  );
}
export function Sheet({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <Modal animationType="slide" transparent onRequestClose={onClose}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.modalBackdrop}
      >
        <View style={styles.sheet}>
          <View style={styles.sheetHead}>
            <Heading small>{title}</Heading>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close dialog"
              onPress={onClose}
              hitSlop={12}
            >
              <Text style={styles.close}>✕</Text>
            </Pressable>
          </View>
          <ScrollView
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={styles.sheetBody}
          >
            {children}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
export function Loading({ text }: { text: string }) {
  return (
    <View style={{ padding: 20, gap: 14 }}>
      <ActivityIndicator color={palette.ink} />
      <Body>{text}</Body>
    </View>
  );
}
export const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: palette.paper },
  screen: { paddingHorizontal: 20, paddingTop: 16, paddingBottom: 24, gap: 14 },
  header: {
    paddingHorizontal: 22,
    paddingTop: 16,
    paddingBottom: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {
    fontSize: 22,
    fontWeight: '800',
    color: palette.ink,
    letterSpacing: -0.8,
  },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 1.5,
    color: palette.muted,
    fontWeight: '700',
    marginTop: 3,
  },
  heading: {
    fontSize: 32,
    fontWeight: '800',
    color: palette.ink,
    letterSpacing: -1.2,
  },
  headingSmall: {
    fontSize: 20,
    fontWeight: '700',
    color: palette.ink,
    letterSpacing: -0.5,
  },
  body: { fontSize: 14, lineHeight: 21, color: palette.muted },
  card: {
    backgroundColor: palette.white,
    borderRadius: 22,
    padding: 20,
    gap: 12,
  },
  button: {
    backgroundColor: palette.coral,
    borderRadius: 14,
    paddingHorizontal: 17,
    minHeight: 48,
    justifyContent: 'center',
    alignItems: 'center',
    marginVertical: 4,
  },
  secondary: {
    backgroundColor: palette.paper,
    borderWidth: 1,
    borderColor: palette.line,
  },
  buttonText: {
    color: palette.ink,
    fontSize: 14,
    fontWeight: '700',
    textAlign: 'center',
  },
  fieldWrap: { gap: 7, marginVertical: 7 },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: palette.ink,
    lineHeight: 19,
  },
  field: {
    backgroundColor: palette.white,
    borderWidth: 1,
    borderColor: palette.line,
    borderRadius: 13,
    minHeight: 50,
    padding: 13,
    fontSize: 16,
    color: palette.ink,
  },
  toggle: {
    flexDirection: 'row',
    gap: 12,
    alignItems: 'center',
    paddingVertical: 9,
  },
  note: { padding: 13, backgroundColor: '#EDF1EB', borderRadius: 12 },
  noteText: { color: palette.ink, fontSize: 12, lineHeight: 18 },
  nav: {
    flexDirection: 'row',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderTopWidth: 1,
    borderTopColor: palette.line,
    backgroundColor: palette.paper,
  },
  navItem: {
    flex: 1,
    alignItems: 'center',
    gap: 4,
    paddingVertical: 9,
    borderRadius: 14,
  },
  navText: { fontSize: 10, fontWeight: '700', color: palette.muted },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
  },
  rowIcon: { fontSize: 23, width: 38, textAlign: 'center' },
  rowName: { color: palette.ink, fontWeight: '600', fontSize: 14 },
  rowSub: { color: palette.muted, fontSize: 11, marginTop: 4 },
  rowMoney: { color: palette.ink, fontSize: 14, fontWeight: '700' },
  divider: { height: 1, backgroundColor: palette.line },
  amount: {
    fontSize: 37,
    color: palette.ink,
    fontWeight: '800',
    letterSpacing: -1.5,
  },
  statRow: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  pill: {
    backgroundColor: palette.mint,
    paddingHorizontal: 11,
    paddingVertical: 6,
    borderRadius: 20,
  },
  pillText: { fontSize: 10, color: palette.ink, fontWeight: '700' },
  modalBackdrop: {
    flex: 1,
    backgroundColor: '#102C3F88',
    justifyContent: 'flex-end',
  },
  sheet: {
    maxHeight: '94%',
    minHeight: '50%',
    borderTopLeftRadius: 26,
    borderTopRightRadius: 26,
    backgroundColor: palette.paper,
  },
  sheetHead: {
    padding: 22,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 15,
    borderBottomWidth: 1,
    borderBottomColor: palette.line,
  },
  sheetBody: { padding: 22, paddingBottom: 45, gap: 12 },
  close: { fontSize: 21, color: palette.ink },
  tabs: { flexDirection: 'row', gap: 10 },
  tab: {
    flex: 1,
    padding: 13,
    backgroundColor: palette.white,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: palette.line,
  },
  barTrack: {
    height: 7,
    borderRadius: 7,
    backgroundColor: palette.paper,
    overflow: 'hidden',
  },
  barFill: { height: 7, backgroundColor: palette.coral, borderRadius: 7 },
});
