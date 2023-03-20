(function () {
    Promise.reject(new Error("Rejected promise"));
    throw new Error("A Javascript error");
})();
