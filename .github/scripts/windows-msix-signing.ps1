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

function Add-KWebMsixTrustedCertificate {
    param([Parameter(Mandatory = $true)][string] $CertificatePath)
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($CertificatePath)
    $store = [Security.Cryptography.X509Certificates.X509Store]::new(
        "TrustedPeople",
        [Security.Cryptography.X509Certificates.StoreLocation]::LocalMachine
    )
    $trusted = @()
    try {
        if ($certificate.HasPrivateKey) { throw "MSIX deployment trust requires a public certificate without its private key." }
        $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        $store.Add($certificate)
        $trusted = @($store.Certificates.Find(
            [Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,
            $certificate.Thumbprint,
            $false
        ))
        if ($trusted.Count -ne 1 -or $trusted[0].HasPrivateKey -or
            [Convert]::ToBase64String($trusted[0].RawData) -ne [Convert]::ToBase64String($certificate.RawData)) {
            throw "The exact public MSIX signing certificate was not imported into LocalMachine\TrustedPeople."
        }
    }
    finally {
        foreach ($entry in $trusted) { $entry.Dispose() }
        $store.Close()
        $certificate.Dispose()
    }
}
