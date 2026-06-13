import 'package:flutter_test/flutter_test.dart';
import 'package:putting_caddy/main.dart';

void main() {
  testWidgets('shell shows launching', (WidgetTester tester) async {
    await tester.pumpWidget(const App());
    expect(find.text('Launching...'), findsOneWidget);
  });
}
