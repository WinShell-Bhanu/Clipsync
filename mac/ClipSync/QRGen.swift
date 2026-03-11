


import Foundation
import SwiftUI


// Purpose: UI component that renders state and user interactions.
// Responsibilities: Encapsulates qrgen screen behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct QRGenScreen: View {

    @State private var titleOpacity: Double = 0
    @State private var titleOffset: CGFloat = -30

    @State private var card1Opacity: Double = 0
    @State private var card1Offset: CGFloat = -20

    @State private var card2Opacity: Double = 0
    @State private var card2Offset: CGFloat = -20

    @State private var card3Opacity: Double = 0
    @State private var card3Offset: CGFloat = -20

    @State private var qrCardOpacity: Double = 0
    @State private var qrCardScale: CGFloat = 0.85


    @State private var selectedCountry: String = ""
    @State private var detectedCountry: String = ""
    @State private var showCountryPicker: Bool = false
    @State private var isDetecting: Bool = true


    @StateObject private var qrGenerator = QRCodeGenerator.shared
    @StateObject private var pairingManager = PairingManager.shared
    @State private var navigateToConnected = false

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {

            MeshBackground()
                .ignoresSafeArea()


            HStack(alignment: .center, spacing: 40) {

                VStack(alignment: .leading, spacing: 40) {

                    Text("One Scan.\nInfinite Sync.")
                        .font(.custom("SF Pro Display", size: 52))
                        .fontWeight(.bold)
                        .kerning(-1.56)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.leading)
                        .frame(width: 350, alignment: .leading)
                        .padding(.bottom, 8)
                        .opacity(titleOpacity)
                        .offset(y: titleOffset)


                    ZStack {
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .fill(Color.white.opacity(0.4))

                        Image("android")
                            .resizable()
                            .renderingMode(.template)
                            .foregroundColor(.black)
                            .frame(width: 32, height: 20)
                            .offset(x: 130, y: 35)

                        HStack(alignment: .center, spacing: 14) {
                            NumberCircleView(number: "1")

                            Text("Open ClipSync app on your\nAndroid Phone")
                                .font(.custom("SF Pro", size: 19))
                                .fontWeight(.medium)
                                .lineSpacing(2)
                                .multilineTextAlignment(.center)
                                .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.horizontal, 16)
                    }
                    .frame(width: 350, height: 90)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .opacity(card1Opacity)
                    .offset(y: card1Offset)


                    HStack(alignment: .center, spacing: 14) {
                        NumberCircleView(number: "2")

                        Text("Tap \"Scan QR\" inside the\napp")
                            .font(.custom("SF Pro", size: 18))
                            .fontWeight(.medium)
                            .lineSpacing(3)
                            .multilineTextAlignment(.leading)
                            .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))

                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .frame(width: 350, height: 80)
                    .background(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .fill(Color.white.opacity(0.4))
                    )
                    .opacity(card2Opacity)
                    .offset(y: card2Offset)


                    HStack(alignment: .center, spacing: 14) {
                        NumberCircleView(number: "3")

                        Text("Point your phone's camera at\nthis QR Code")
                            .font(.custom("SF Pro", size: 18))
                            .fontWeight(.medium)
                            .lineSpacing(3)
                            .multilineTextAlignment(.leading)
                            .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))

                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .frame(width: 350, height: 80)
                    .background(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .fill(Color.white.opacity(0.4))
                    )
                    .opacity(card3Opacity)
                    .offset(y: card3Offset)
                }
                .frame(width: 350)
                .offset(y: 20)


                VStack(alignment: .center, spacing: 16) {

                    Button(action: {
                        showCountryPicker.toggle()
                    }) {
                        HStack(spacing: 8) {
                            if isDetecting {
                                ProgressView()
                                    .scaleEffect(0.6)
                                    .frame(width: 14, height: 14)
                            } else {
                                Image(systemName: "globe")
                                    .font(.system(size: 14))
                            }

                            Text(selectedCountry.isEmpty ? "Detecting location..." : selectedCountry)
                                .font(.custom("SF Pro", size: 14))
                                .fontWeight(.medium)
                                .lineLimit(1)

                            if !isDetecting {
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 10))
                            }
                        }
                        .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .frame(minWidth: 170)
                        .background(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .fill(Color.white.opacity(0.5))
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(isDetecting)
                    .popover(isPresented: $showCountryPicker) {
                        CountryPickerView(
                            selectedCountry: $selectedCountry,
                            detectedCountry: detectedCountry,
                            onSelect: { country in
                                selectedCountry = country
                                showCountryPicker = false
                                updateServerRegion(for: country, userInitiated: true)
                            }
                        )
                    }


                    ZStack {
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .fill(Color.white.opacity(0.4))

                        VStack(spacing: 10) {
                            Group {
                                if let qrImage = qrGenerator.qrImage {
                                    Image(nsImage: qrImage)
                                        .interpolation(.none)
                                        .resizable()
                                } else {
                                    ProgressView()
                                        .scaleEffect(1.5)
                                }
                            }
                            .frame(width: 140, height: 140)

                            ZStack {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(Color(red: 0.576, green: 0.647, blue: 0.816).opacity(0.5))
                                    .frame(width: 140, height: 30)

                                Text(DeviceManager.shared.getFriendlyMacName())
                                    .font(.custom("SF Pro", size: 14))
                                    .fontWeight(.medium)
                                    .foregroundColor(.white)
                            }
                            .padding(.top, 10)
                        }
                    }
                    .frame(width: 170, height: 210)
                    .opacity(qrCardOpacity)
                    .scaleEffect(qrCardScale)


                    if !selectedCountry.isEmpty && !isDetecting {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 6, height: 6)

                            let region = UserDefaults.standard.string(forKey: "server_region") ?? "IN"
                            Text("Connected to \(region == "US" ? "🇺🇸 US" : "🇮🇳 India") server")
                                .font(.custom("SF Pro", size: 11))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(
                            Capsule()
                                .fill(Color.black.opacity(0.3))
                        )
                    }
                }
                .frame(width: 170)
                .offset(y: 60)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)


            if !pairingManager.isPaired {
                VStack {
                    Spacer()

                    HStack {
                        ProgressView()
                            .scaleEffect(0.7)
                        Text("Waiting for phone to scan...")
                            .font(.system(size: 13))
                            .foregroundColor(.white.opacity(0.8))
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.5))
                    .cornerRadius(20)
                    .padding(.bottom, 20)
                }
            }
        }
        .frame(width: 590, height: 590)
        .onAppear {
            detectAndSetupRegion()
            let macDeviceId = DeviceManager.shared.getDeviceId()
            pairingManager.listenForPairing(macDeviceId: macDeviceId)
            playEntranceAnimations()
        }
        .onChange(of: pairingManager.isPaired) { oldValue, newValue in
            // Bug fix: only navigate when isPaired transitions false → true.
            // Without this guard, if QRGenScreen appears while isPaired is already
            // true (race with clearPairing), it would immediately navigate to
            // ConnectedScreen before the user even scans the QR code.
            if oldValue == false && newValue == true {
                navigateToConnected = true
            }
        }
        .navigationDestination(isPresented: $navigateToConnected) {
            ConnectedScreen()
        }
        .enableInjection()
    }


    // Purpose: Implements the detect and setup region operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func detectAndSetupRegion() {
        if let savedCountry = UserDefaults.standard.string(forKey: "selected_country_name") {
            self.selectedCountry = savedCountry
            self.detectedCountry = savedCountry
            self.isDetecting = false
            self.updateServerRegion(for: savedCountry)
            return
        }

        isDetecting = true

        LocationHelper.shared.detectRegion { countryCode in
            DispatchQueue.main.async {
                self.isDetecting = false

                if let countryCode = countryCode {
                    let countryName = self.findCountryName(from: countryCode)
                    self.detectedCountry = countryName
                    self.selectedCountry = countryName
                    self.updateServerRegion(for: countryName)
                } else {
                    self.selectedCountry = "India"
                    self.detectedCountry = "India"
                    self.updateServerRegion(for: "India")
                }
            }
        }
    }


    // Purpose: Implements the find country name operation for this feature.
    // Parameters: code.
    // Returns: String.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func findCountryName(from code: String) -> String {
        let codeToName: [String: String] = [

            "US": "United States", "CA": "Canada", "MX": "Mexico", "BR": "Brazil",
            "AR": "Argentina", "CL": "Chile", "CO": "Colombia", "PE": "Peru",
            "VE": "Venezuela", "EC": "Ecuador", "UY": "Uruguay", "CR": "Costa Rica",
            "PA": "Panama", "GT": "Guatemala", "CU": "Cuba", "DO": "Dominican Republic",
            "HN": "Honduras", "NI": "Nicaragua", "SV": "El Salvador", "PY": "Paraguay",
            "BO": "Bolivia", "JM": "Jamaica", "TT": "Trinidad and Tobago",


            "GB": "United Kingdom", "UK": "United Kingdom", "DE": "Germany",
            "FR": "France", "IT": "Italy", "ES": "Spain", "NL": "Netherlands",
            "SE": "Sweden", "NO": "Norway", "DK": "Denmark", "FI": "Finland",
            "BE": "Belgium", "AT": "Austria", "CH": "Switzerland", "IE": "Ireland",
            "PT": "Portugal", "PL": "Poland", "GR": "Greece", "CZ": "Czech Republic",
            "HU": "Hungary", "RO": "Romania", "BG": "Bulgaria", "HR": "Croatia",
            "RS": "Serbia", "UA": "Ukraine", "SK": "Slovakia", "SI": "Slovenia",
            "EE": "Estonia", "LV": "Latvia", "LT": "Lithuania", "IS": "Iceland",
            "LU": "Luxembourg", "CY": "Cyprus", "MT": "Malta", "BA": "Bosnia and Herzegovina",
            "AL": "Albania", "MK": "North Macedonia", "ME": "Montenegro", "MD": "Moldova",
            "BY": "Belarus",


            "IN": "India", "CN": "China", "JP": "Japan", "KR": "South Korea",
            "SG": "Singapore", "TH": "Thailand", "MY": "Malaysia", "VN": "Vietnam",
            "PH": "Philippines", "ID": "Indonesia", "PK": "Pakistan", "BD": "Bangladesh",
            "LK": "Sri Lanka", "MM": "Myanmar", "KH": "Cambodia", "LA": "Laos",
            "HK": "Hong Kong", "TW": "Taiwan", "NP": "Nepal", "BT": "Bhutan",
            "AF": "Afghanistan", "MN": "Mongolia", "BN": "Brunei", "MO": "Macao",
            "MV": "Maldives", "TL": "Timor-Leste",


            "AU": "Australia", "NZ": "New Zealand", "PG": "Papua New Guinea",
            "FJ": "Fiji", "SB": "Solomon Islands", "WS": "Samoa", "TO": "Tonga",
            "VU": "Vanuatu",


            "AE": "United Arab Emirates", "SA": "Saudi Arabia", "QA": "Qatar",
            "KW": "Kuwait", "OM": "Oman", "BH": "Bahrain", "JO": "Jordan",
            "LB": "Lebanon", "IR": "Iran", "IQ": "Iraq", "YE": "Yemen",
            "SY": "Syria", "TR": "Turkey", "IL": "Israel",


            "ZA": "South Africa", "EG": "Egypt", "NG": "Nigeria", "KE": "Kenya",
            "MA": "Morocco", "DZ": "Algeria", "TN": "Tunisia", "LY": "Libya",
            "GH": "Ghana", "ET": "Ethiopia", "TZ": "Tanzania", "UG": "Uganda",
            "AO": "Angola", "MZ": "Mozambique", "ZW": "Zimbabwe", "ZM": "Zambia",
            "SN": "Senegal", "CI": "Ivory Coast", "CM": "Cameroon", "BW": "Botswana",
            "NA": "Namibia", "MU": "Mauritius", "RW": "Rwanda", "MW": "Malawi",
            "MG": "Madagascar", "ML": "Mali", "BF": "Burkina Faso", "NE": "Niger",
            "TD": "Chad", "CG": "Congo", "CD": "Democratic Republic of the Congo",
            "SD": "Sudan",


            "RU": "Russia", "KZ": "Kazakhstan", "UZ": "Uzbekistan",
            "TM": "Turkmenistan", "KG": "Kyrgyzstan", "TJ": "Tajikistan",
            "AZ": "Azerbaijan", "AM": "Armenia", "GE": "Georgia"
        ]

        return codeToName[code] ?? "India"
    }


    // Purpose: Updates server region based on current inputs.
    // Parameters: country.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func updateServerRegion(for country: String, userInitiated: Bool = false) {
        let newRegion = RegionConfig.getOptimalServer(for: country)
        let currentRegion = UserDefaults.standard.string(forKey: "server_region") ?? "IN"

        UserDefaults.standard.set(country, forKey: "selected_country_name")

        if newRegion != currentRegion {
            UserDefaults.standard.set(newRegion, forKey: "server_region")
            UserDefaults.standard.synchronize()
            // Only restart when user explicitly changes region — never on auto-detection at startup
            if userInitiated {
                restartApp()
            } else {
                qrGenerator.generateQRCode()
            }
        } else {
            qrGenerator.generateQRCode()
        }
    }


    // Purpose: Implements the restart app operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func restartApp() {
        let url = URL(fileURLWithPath: Bundle.main.bundlePath)
        let config = NSWorkspace.OpenConfiguration()
        config.activates = true

        NSWorkspace.shared.openApplication(at: url, configuration: config) { _, _ in
            DispatchQueue.main.async {
                NSApp.terminate(nil)
            }
        }
    }


    // Purpose: Implements the play entrance animations operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func playEntranceAnimations() {
        withAnimation(.spring(response: 0.8, dampingFraction: 0.75, blendDuration: 0).delay(0.1)) {
            titleOpacity = 1
            titleOffset = 0
        }

        withAnimation(.spring(response: 0.7, dampingFraction: 0.75, blendDuration: 0).delay(0.2)) {
            card1Opacity = 1
            card1Offset = 0
        }

        withAnimation(.spring(response: 0.7, dampingFraction: 0.75, blendDuration: 0).delay(0.35)) {
            card2Opacity = 1
            card2Offset = 0
        }

        withAnimation(.spring(response: 0.7, dampingFraction: 0.75, blendDuration: 0).delay(0.5)) {
            card3Opacity = 1
            card3Offset = 0
        }

        withAnimation(.spring(response: 0.8, dampingFraction: 0.7, blendDuration: 0).delay(0.65)) {
            qrCardOpacity = 1
            qrCardScale = 1.0
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            startQRFloat()
        }
    }


    // Purpose: Starts qrfloat flow and required listeners.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func startQRFloat() {
        withAnimation(.easeInOut(duration: 2.5).repeatForever(autoreverses: true)) {
            qrCardScale = 1.02
        }
    }
}


// Purpose: UI component that renders state and user interactions.
// Responsibilities: Encapsulates country picker view behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct CountryPickerView: View {
    @Binding var selectedCountry: String
    let detectedCountry: String
    let onSelect: (String) -> Void

    @State private var searchText = ""

    var filteredCountries: [String] {
        let countries = RegionConfig.sortedCountryNames
        if searchText.isEmpty {
            return countries
        }
        return countries.filter { $0.localizedCaseInsensitiveContains(searchText) }
    }

    var body: some View {
        VStack(spacing: 0) {

            HStack {
                Text("Select Your Country")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.primary)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color(NSColor.controlBackgroundColor))

            Divider()


            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search countries...", text: $searchText)
                    .textFieldStyle(.plain)

                if !searchText.isEmpty {
                    Button(action: { searchText = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(8)
            .background(Color(NSColor.textBackgroundColor))
            .cornerRadius(8)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)


            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(filteredCountries, id: \.self) { country in
                        Button(action: {
                            onSelect(country)
                        }) {
                            HStack {
                                Text(country)
                                    .font(.system(size: 14))
                                    .foregroundColor(.primary)

                                Spacer()

                                if country == detectedCountry {
                                    Text("Auto-detected")
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Color.blue.opacity(0.1))
                                        .cornerRadius(6)
                                }

                                if country == selectedCountry {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                        .font(.system(size: 14, weight: .semibold))
                                }


                                let server = RegionConfig.getOptimalServer(for: country)
                                Text(server == "US" ? "🇺🇸" : "🇮🇳")
                                    .font(.system(size: 14))
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(
                                country == selectedCountry ?
                                Color.blue.opacity(0.1) :
                                Color.clear
                            )
                        }
                        .buttonStyle(.plain)

                        if country != filteredCountries.last {
                            Divider()
                                .padding(.leading, 16)
                        }
                    }
                }
            }
            .frame(maxHeight: 350)
        }
        .frame(width: 340)
        .background(Color(NSColor.windowBackgroundColor))
    }
}


// Purpose: UI component that renders state and user interactions.
// Responsibilities: Encapsulates number circle view behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
private struct NumberCircleView: View {
    let number: String
    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {
            Circle()
                .fill(.regularMaterial)

            Circle()
                .fill(
                    LinearGradient(
                        colors: [Color.white.opacity(0.7), Color.white.opacity(0.1)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )

            Circle()
                .strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0.9), Color.white.opacity(0.3)],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
                .blendMode(.overlay)

            Text(number)
                .font(.custom("SF Pro", size: 16))
                .fontWeight(.bold)
                .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
        }
        .frame(width: 34, height: 34)
        .environment(\.colorScheme, .light)
        .enableInjection()
    }
}

#Preview {
    QRGenScreen()
}
