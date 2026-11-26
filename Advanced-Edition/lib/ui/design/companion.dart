import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'appearance.dart';

enum CompanionMood { welcome, stop, rest, sleep, help, celebrate }

/// One atlas keeps native block screens and Flutter illustrations consistent.
class Companion extends StatefulWidget {
  const Companion({
    super.key,
    this.mood = CompanionMood.welcome,
    this.size = 160,
    this.animate = true,
  });
  final CompanionMood mood;
  final double size;
  final bool animate;
  @override
  State<Companion> createState() => _CompanionState();
}

class _CompanionState extends State<Companion> {
  ImageStream? _stream;
  ImageStreamListener? _listener;
  ui.Image? _image;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_stream != null) return;
    _stream = const AssetImage(
      'assets/illustrations/companion-atlas.png',
    ).resolve(createLocalImageConfiguration(context));
    _listener = ImageStreamListener(
      (info, _) {
        if (mounted) setState(() => _image = info.image);
      },
      onError: (Object error, StackTrace? stack) {
        // Keep the message and actions usable if decoding artwork fails.
      },
    );
    _stream!.addListener(_listener!);
  }

  @override
  void dispose() {
    if (_listener != null) _stream?.removeListener(_listener!);
    _image?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduced =
        MediaQuery.disableAnimationsOf(context) ||
        (AppearanceScope.maybeOf(context)?.reduceMotion ?? false);
    return ExcludeSemantics(
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: widget.animate && !reduced ? .88 : 1, end: 1),
        duration: reduced ? Duration.zero : const Duration(milliseconds: 420),
        curve: Curves.easeOutCubic,
        builder: (context, value, child) => Opacity(
          opacity: value,
          child: Transform.scale(scale: value, child: child),
        ),
        child: SizedBox(
          width: widget.size,
          height: widget.size,
          child: _image == null
              ? Icon(
                  Icons.auto_awesome_rounded,
                  size: widget.size * .3,
                  color: Theme.of(context).colorScheme.primary,
                )
              : CustomPaint(
                  painter: _PortraitPainter(_image!, widget.mood.index),
                ),
        ),
      ),
    );
  }
}

class _PortraitPainter extends CustomPainter {
  _PortraitPainter(this.image, this.index);
  final ui.Image image;
  final int index;
  @override
  void paint(Canvas canvas, Size size) {
    final w = image.width / 3;
    final h = image.height / 2;
    final source = Rect.fromLTWH(index % 3 * w, index ~/ 3 * h, w, h);
    final fitted = applyBoxFit(BoxFit.contain, source.size, size);
    final target = Alignment.center.inscribe(
      fitted.destination,
      Offset.zero & size,
    );
    canvas.save();
    canvas.clipRRect(
      RRect.fromRectAndRadius(target, const Radius.circular(20)),
    );
    canvas.drawImageRect(
      image,
      source,
      target,
      Paint()..filterQuality = FilterQuality.medium,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(_PortraitPainter oldDelegate) =>
      image != oldDelegate.image || index != oldDelegate.index;
}

class CompanionBanner extends StatelessWidget {
  const CompanionBanner({
    super.key,
    required this.title,
    required this.message,
    this.mood = CompanionMood.welcome,
  });
  final String title;
  final String message;
  final CompanionMood mood;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            colors.primary.withValues(alpha: .19),
            colors.secondary.withValues(alpha: .07),
          ],
        ),
        border: Border.all(color: colors.primary.withValues(alpha: .16)),
      ),
      padding: const EdgeInsets.all(20),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final text = Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                message,
                style: TextStyle(color: colors.onSurfaceVariant, height: 1.45),
              ),
            ],
          );
          if (constraints.maxWidth < 300 ||
              MediaQuery.textScalerOf(context).scale(1) > 1.3) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Companion(mood: mood, size: 120),
                text,
              ],
            );
          }
          return Row(
            children: [
              Expanded(child: text),
              const SizedBox(width: 8),
              Companion(mood: mood, size: 130),
            ],
          );
        },
      ),
    );
  }
}

class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.subtitle});
  final String text;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          text,
          style: Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
        ),
        if (subtitle != null) ...[
          const SizedBox(height: 4),
          Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    ),
  );
}

class CompanionDialog extends StatelessWidget {
  const CompanionDialog({
    super.key,
    required this.title,
    required this.content,
    this.actions = const [],
    this.mood = CompanionMood.help,
  });
  final Widget title;
  final Widget content;
  final List<Widget> actions;
  final CompanionMood mood;
  @override
  Widget build(BuildContext context) => AlertDialog(
    icon: Companion(mood: mood, size: 88),
    title: title,
    content: content,
    actions: actions,
    scrollable: true,
  );
}

void companionNotice(
  BuildContext context,
  String message, {
  CompanionMood mood = CompanionMood.help,
}) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Row(
        children: [
          Companion(mood: mood, size: 48, animate: false),
          const SizedBox(width: 10),
          Expanded(child: Text(message)),
        ],
      ),
    ),
  );
}
