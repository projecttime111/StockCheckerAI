import { Text } from "react-native";
import { Screen } from "@/src/components/ui";

export default function InvoiceUploadScreen() {
  return (
    <Screen title="Invoice Upload">
      <Text accessibilityRole="alert" style={{ color: "#53645d" }}>
        Invoice extraction is unavailable. Use an ERP export in the web import workspace.
      </Text>
    </Screen>
  );
}
