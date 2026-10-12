class RedirectViewNameFromParam {
    String afterLogin(String returnUrl) {
        return "redirect:" + returnUrl;
    }
}
