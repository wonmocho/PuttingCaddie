import 'package:flutter/material.dart';

void main() => runApp(const App());

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    // 실제 화면은 Android 쪽 MainActivity가 즉시 네이티브 Activity로 넘깁니다.
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Center(
          child: Text('Launching...'),
        ),
      ),
    );
  }
}
