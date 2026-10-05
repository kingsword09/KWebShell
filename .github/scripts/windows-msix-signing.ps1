function New-KWebMsixSigningCertificate {
    param([Parameter(Mandatory = $true)][string] $Publisher)
    New-SelfSignedCertificate `
        -Type Custom `
        -Subject $Publisher `
        -FriendlyName "KWebShell RFC 0030 hosted-test signing" `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -KeyUsage DigitalSignature `
        -KeyExportPolicy Exportable `
        -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}") `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -NotAfter (Get-Date).AddDays(2)
}
