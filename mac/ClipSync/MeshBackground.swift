


import SwiftUI


// Purpose: Struct that models mesh background behavior in this module.
// Responsibilities: Encapsulates mesh background behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct MeshBackground: View {

    let lightBlue = Color(red: 0.855, green: 1.0, blue: 0.992)
    let midBlue   = Color(red: 0.569, green: 0.675, blue: 0.992)
    let darkBlue  = Color(red: 0.376, green: 0.490, blue: 0.996)
    let baseColor = Color(red: 0.693, green: 0.761, blue: 0.965)


    var introProgress: CGFloat = 1.0


    var shouldAnimate: Bool = true

    @State private var animate = false
    @State private var isTouched = false

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {


            baseColor
                .opacity(introProgress)
                .ignoresSafeArea()


            blob(
                color: lightBlue.opacity(0.9),
                baseSize: 600,
                baseBlur: 60,
                posA: CGPoint(x: -150, y: -200),
                posB: CGPoint(x: 150,  y: 100),
                duration: 1.5
            )

            blob(
                color: midBlue.opacity(0.8),
                baseSize: 700,
                baseBlur: 70,
                posA: CGPoint(x: 200,  y: 100),
                posB: CGPoint(x: -100, y: -150),
                duration: 2.0
            )

            blob(
                color: darkBlue.opacity(0.7),
                baseSize: 600,
                baseBlur: 80,
                posA: CGPoint(x: -100, y: 250),
                posB: CGPoint(x: 200,  y: -100),
                duration: 2.5
            )
        }
        .drawingGroup()
        .onAppear {
            if shouldAnimate {
                animate = true
            }
        }
        .onChange(of: shouldAnimate) { _, newValue in
            if newValue && !animate {
                animate = true
            }
        }
        .onTapGesture {
            isTouched = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                isTouched = false
            }
        }
        .enableInjection()
    }


    // Purpose: Implements the blob operation for this feature.
    // Parameters: See signature for parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func blob(
        color: Color,
        baseSize: CGFloat,
        baseBlur: CGFloat,
        posA: CGPoint,
        posB: CGPoint,
        duration: Double
    ) -> some View {
        let p = clamp01(introProgress)


        let size = lerp(baseSize * 0.02, baseSize, p)


        let blur = lerp(baseBlur * 0.5, baseBlur, p)


        let targetOffset = animate ? posA : posB
        let ox = lerp(0, targetOffset.x, p)
        let oy = lerp(0, targetOffset.y, p)

        return Circle()
            .fill(color)
            .frame(width: size, height: size)
            .blur(radius: blur)
            .scaleEffect(isTouched ? 1.12 : 1.0)
            .offset(x: ox, y: oy)
            .animation(
                shouldAnimate ? .easeInOut(duration: duration).repeatForever(autoreverses: true) : nil,
                value: animate
            )
            .animation(.spring(response: 0.3, dampingFraction: 0.55), value: isTouched)
    }


    // Purpose: Implements the lerp operation for this feature.
    // Parameters: a, b, t.
    // Returns: CGFloat.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat {
        a + (b - a) * t
    }


    // Purpose: Implements the clamp01 operation for this feature.
    // Parameters: x.
    // Returns: CGFloat.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func clamp01(_ x: CGFloat) -> CGFloat {
        min(max(x, 0), 1)
    }
}

#Preview {
    MeshBackground(introProgress: 1.0, shouldAnimate: true)
        .frame(width: 590, height: 590)
}

